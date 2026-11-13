package org.opentmf.cadenzaflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ENGINE-JOBS-IDLE-IN-TRANSACTION-1, measured as a container stress run (Cezmi's ruling on
 * the row's Q3): does the engine leave sessions {@code idle in transaction} under a burst of
 * jobs, on the pool settings the image ships (auto-commit off, no Hikari {@code schema})?
 *
 * <p>The W1 load run of 2026-10-13 saw 8 to 22 such sessions for the engine's role on engine
 * 1.4.1, whose Hikari {@code schema} setting with auto-commit off was the cause found for the
 * adapters; CF-SCHEMA-FROM-URL-1 (1.5.0) removed that setting. This run samples
 * {@code pg_stat_activity} for the engine's role every second in three phases - at rest,
 * under a burst of {@value #INSTANCES} instances with two async jobs each, and in the drain -
 * while the wrapper's own report query also runs on the pool. A FINDING is a session
 * {@code idle in transaction} for {@value #FINDING_AGE_SECONDS} s or longer (the measurement
 * procedure, {@code docs/engine-idle-in-transaction-measurement.md}); a younger one is a
 * transaction caught between two statements, counted but not a finding.</p>
 *
 * <p>Two runs of the same scenario: {@link EngineIdleInTransactionStressIT} on the image's
 * pool, which must show no finding, and {@link EngineIdleInTransactionControlIT} with the
 * Hikari {@code schema} of 1.4.1 put back, which must show one - the proof that the sampler
 * sees the defect it is looking for.</p>
 *
 * @author Cezmi Aslan
 */
abstract class EngineIdleInTransactionStressScenario {

  private static final Logger LOG =
      LoggerFactory.getLogger(EngineIdleInTransactionStressScenario.class);

  static final int INSTANCES = 300;
  static final int FINDING_AGE_SECONDS = 5;
  private static final Duration REST = Duration.ofSeconds(15);
  private static final Duration DRAIN = Duration.ofSeconds(15);
  private static final String KEY = "stressFlow";

  /** Two async service tasks: every instance gives the job executor two jobs, one after the other. */
  private static final String FLOW = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                        xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                        targetNamespace="http://opentmf.org/idle-in-transaction-stress">
        <bpmn:process id="stressFlow" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="first"/>
          <bpmn:serviceTask id="first" camunda:asyncBefore="true"
                            camunda:expression="${execution.setVariable('first', true)}"/>
          <bpmn:sequenceFlow id="f2" sourceRef="first" targetRef="second"/>
          <bpmn:serviceTask id="second" camunda:asyncBefore="true"
                            camunda:expression="${execution.setVariable('second', true)}"/>
          <bpmn:sequenceFlow id="f3" sourceRef="second" targetRef="end"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /** One row per client backend of the engine's role, the sampler's own session excluded. */
  private static final String SAMPLE = """
      select state,
             coalesce(extract(epoch from now() - state_change), 0) as state_age_s,
             left(regexp_replace(coalesce(query, ''), '\\s+', ' ', 'g'), 200) as last_query
      from   pg_stat_activity
      where  usename = current_user
      and    backend_type = 'client backend'
      and    pid <> pg_backend_pid()
      """;

  @Autowired
  private ProcessEngine processEngine;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private IncidentGroupRepository incidentGroupRepository;

  /** What the sampler saw in one phase. */
  static final class Phase {
    final String name;
    int samples;
    int maxSessions;
    int maxIdleInTransaction;
    final Map<String, Double> findings = new TreeMap<>();

    Phase(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name + ": " + samples + " samples, max " + maxSessions + " sessions, max "
          + maxIdleInTransaction + " idle in transaction in one sample, findings " + findings;
    }
  }

  /** The pool the run uses, as Spring built it. */
  HikariDataSource pool() throws SQLException {
    return dataSource.unwrap(HikariDataSource.class);
  }

  /** Runs rest, burst and drain and answers what the sampler saw in each. */
  List<Phase> runPhases() throws Exception {
    assertThat(pool().isAutoCommit()).as("auto-commit off, as the image ships it").isFalse();

    processEngine.getRepositoryService().createDeployment()
        .name(KEY).addString(KEY + ".bpmn20.xml", FLOW).deploy();

    List<Phase> phases = new ArrayList<>();
    try (Connection sampler = dataSource.getConnection()) {
      sampler.setAutoCommit(true);

      phases.add(sample(sampler, "rest", () -> false, REST));

      for (int i = 0; i < INSTANCES; i++) {
        processEngine.getRuntimeService().startProcessInstanceByKey(KEY);
      }
      phases.add(sample(sampler, "burst", this::burstIsOver, Duration.ofMinutes(5)));
      assertThat(burstIsOver()).as("every job of the burst has run").isTrue();
      assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
          .processDefinitionKey(KEY).finished().count()).isEqualTo(INSTANCES);

      phases.add(sample(sampler, "drain", () -> false, DRAIN));
    }

    phases.forEach(phase -> LOG.info("ENGINE-JOBS-IDLE-IN-TRANSACTION-1 [{}] {}",
        getClass().getSimpleName(), phase));
    assertThat(phases).allSatisfy(phase -> assertThat(phase.samples).as(phase.name).isPositive());
    return phases;
  }

  private boolean burstIsOver() {
    return processEngine.getManagementService().createJobQuery()
        .processDefinitionKey(KEY).count() == 0
        && processEngine.getRuntimeService().createProcessInstanceQuery()
        .processDefinitionKey(KEY).count() == 0;
  }

  /**
   * Samples once a second until {@code done} or {@code limit}; the wrapper's own report query
   * runs on the same pool every second meanwhile, the second non-engine path the procedure names.
   */
  private Phase sample(Connection sampler, String name, BooleanSupplier done,
      Duration limit) throws Exception {
    Phase phase = new Phase(name);
    AtomicReference<Exception> reportFailure = new AtomicReference<>();
    ScheduledExecutorService reports = Executors.newSingleThreadScheduledExecutor();
    reports.scheduleAtFixedRate(() -> {
      try {
        incidentGroupRepository.groups(KEY, null, null, null, null, null);
      } catch (Exception e) {
        reportFailure.compareAndSet(null, e);
      }
    }, 0, 1, TimeUnit.SECONDS);
    try {
      Instant end = Instant.now().plus(limit);
      Awaitility.await().atMost(limit.plusSeconds(5)).pollInterval(Duration.ofSeconds(1))
          .until(() -> {
            read(sampler, phase);
            return done.getAsBoolean() || Instant.now().isAfter(end);
          });
    } finally {
      reports.shutdownNow();
      assertThat(reports.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(reportFailure.get()).as("the report query ran in phase %s", name).isNull();
    return phase;
  }

  private static void read(Connection sampler, Phase phase) throws SQLException {
    int sessions = 0;
    int idleInTransaction = 0;
    try (PreparedStatement statement = sampler.prepareStatement(SAMPLE);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        sessions++;
        if ("idle in transaction".equals(rows.getString("state"))) {
          idleInTransaction++;
          double age = rows.getDouble("state_age_s");
          if (age >= FINDING_AGE_SECONDS) {
            phase.findings.merge(rows.getString("last_query"), age, Math::max);
          }
        }
      }
    }
    phase.samples++;
    phase.maxSessions = Math.max(phase.maxSessions, sessions);
    phase.maxIdleInTransaction = Math.max(phase.maxIdleInTransaction, idleInTransaction);
  }
}
