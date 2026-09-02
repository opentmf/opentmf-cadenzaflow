package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;
import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The plan-review probe from docs/incident-operations-plan.md §4.2 (❓): loads a
 * DNMS-shaped synthetic dataset into the real engine schema and records the
 * {@code EXPLAIN (ANALYZE, BUFFERS)} plans of the grouped-report and retry selects,
 * plus their actual results and wall times. Its OUTPUT is the deliverable — paste
 * {@code target/incident-group-query-plan.txt} into the PR description.
 *
 * <p><b>Deliberately not part of any build or verify run</b>: it exists to be read,
 * not to gate, and plan shape at this synthetic size is evidence, not a contract —
 * the production planner decides on production statistics. Run it on demand with:</p>
 *
 * <pre>
 * mvn verify -Dincident.query.plan.probe=true \
 *     -Dit.test=IncidentGroupQueryPlanProbeIT -Dtest=NoneMatching \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
 * </pre>
 *
 * <p>Dataset shape (override sizes with {@code -Dprobe.roots=} / {@code -Dprobe.incidents=}):
 * one root definition, a child definition in two versions called from the root's
 * {@code reserve} call activity; {@code probe.roots} root process instances of which
 * {@code probe.incidents} have a failed child — one originating incident on the child
 * plus the engine's propagated copy on the parent, exactly as
 * {@code IncidentEntity.createRecursiveIncidents()} writes them.</p>
 *
 * @author Cezmi Aslan
 */
@EnabledIfSystemProperty(named = "incident.query.plan.probe", matches = "true")
class IncidentGroupQueryPlanProbeIT {

  private static final Logger log = LoggerFactory.getLogger(IncidentGroupQueryPlanProbeIT.class);

  private static final int ROOTS = Integer.getInteger("probe.roots", 800_000);
  private static final int INCIDENTS = Integer.getInteger("probe.incidents", 300_000);

  private final StringBuilder report = new StringBuilder();

  @Test
  void recordTheQueryPlansOnADnmsShapedDataset() throws Exception {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.0-alpine")) {
      postgres.start();
      try (Connection connection = DriverManager.getConnection(
          postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        createEngineSchema(connection);
        loadSyntheticData(connection);

        IncidentGroupRepository repository = repositoryOn(connection);

        // Correctness at scale: per-version rows, originating incidents only.
        long start = System.nanoTime();
        List<IncidentGroupRow> rows = repository.groups("rootFlow", null, null, null, null, null);
        long groupsMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().mapToLong(IncidentGroupRow::incidentCount).sum())
            .as("propagated copies must not be counted")
            .isEqualTo(INCIDENTS);
        assertThat(rows).allSatisfy(row -> {
          assertThat(row.processDefinitionKey()).isEqualTo("childFlow");
          assertThat(row.activityId()).isEqualTo("callWms");
          assertThat(row.calledFromProcessDefinitionKey()).isEqualTo("rootFlow");
          assertThat(row.callActivityId()).isEqualTo("reserve");
        });

        IncidentGroupRetryRequest selector = new IncidentGroupRetryRequest(
            "rootFlow", "childFlow", "callWms", "failedExternalTask", null, null,
            null, null, 1);
        start = System.nanoTime();
        List<String> configurations = repository.retryConfigurations(selector);
        long retryMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(configurations).hasSize(INCIDENTS);

        report.append("Dataset: ").append(ROOTS).append(" root instances, ")
            .append(INCIDENTS).append(" originating incidents (+ copies), ")
            .append("PostgreSQL ").append(postgres.getDockerImageName()).append('\n')
            .append("groups() took ").append(groupsMillis).append(" ms, ")
            .append("retryConfigurations() took ").append(retryMillis).append(" ms\n\n");

        explain(connection, "grouped report (§4.2)",
            captureGroupsSql(), new MapSqlParameterSource("rootKey", "rootFlow"));
        explain(connection, "retry id select (§5.2)",
            captureRetrySql(selector), new MapSqlParameterSource()
                .addValue("rootKey", "rootFlow")
                .addValue("defKey", "childFlow")
                .addValue("activityId", "callWms")
                .addValue("incidentType", "failedExternalTask"));
      }
    }

    Path out = Path.of("target", "incident-group-query-plan.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    log.info("Query plans recorded to {}:\n{}", out.toAbsolutePath(), report);
  }

  private static void createEngineSchema(Connection connection) throws Exception {
    String script = "org/cadenzaflow/bpm/engine/db/create/activiti.postgres.create.engine.sql";
    try (InputStream in = ProcessEngineException.class.getClassLoader()
        .getResourceAsStream(script);
        Statement statement = connection.createStatement()) {
      if (in == null) {
        throw new IllegalStateException("engine DDL not found on classpath: " + script);
      }
      statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /**
   * Pure set-based SQL (generate_series), so the load runs in seconds. Root instances
   * beyond the incident count stay healthy — realistic selectivity for the planner.
   * The child alternates between two definition versions to exercise the roll-up path.
   */
  private static void loadSyntheticData(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          insert into ACT_RE_DEPLOYMENT (ID_, NAME_, DEPLOY_TIME_)
          values ('dep-1', 'query-plan-probe', now());
          insert into ACT_RE_PROCDEF
              (ID_, REV_, KEY_, NAME_, VERSION_, DEPLOYMENT_ID_, SUSPENSION_STATE_) values
              ('rootFlow:1:d1',  1, 'rootFlow',  'Root flow',  1, 'dep-1', 1),
              ('childFlow:7:d1', 1, 'childFlow', 'Child flow', 7, 'dep-1', 1),
              ('childFlow:8:d1', 1, 'childFlow', 'Child flow', 8, 'dep-1', 1);
          """);
      statement.execute("""
          insert into ACT_RU_EXECUTION
              (ID_, REV_, ROOT_PROC_INST_ID_, PROC_INST_ID_, PROC_DEF_ID_, ACT_ID_,
               IS_ACTIVE_, IS_CONCURRENT_, IS_SCOPE_, IS_EVENT_SCOPE_, SUSPENSION_STATE_)
          select 'root-'||g, 1, 'root-'||g, 'root-'||g, 'rootFlow:1:d1',
                 case when g <= %d then 'reserve' end,
                 true, false, true, false, 1
          from generate_series(1, %d) g
          """.formatted(INCIDENTS, ROOTS));
      statement.execute("""
          insert into ACT_RU_EXECUTION
              (ID_, REV_, ROOT_PROC_INST_ID_, PROC_INST_ID_, PROC_DEF_ID_, SUPER_EXEC_,
               ACT_ID_, IS_ACTIVE_, IS_CONCURRENT_, IS_SCOPE_, IS_EVENT_SCOPE_,
               SUSPENSION_STATE_)
          select 'child-'||g, 1, 'root-'||g, 'child-'||g,
                 case when g %% 5 = 0 then 'childFlow:7:d1' else 'childFlow:8:d1' end,
                 'root-'||g, 'callWms', true, false, true, false, 1
          from generate_series(1, %d) g
          """.formatted(INCIDENTS));
      statement.execute("""
          insert into ACT_RU_INCIDENT
              (ID_, REV_, INCIDENT_TIMESTAMP_, INCIDENT_MSG_, INCIDENT_TYPE_,
               EXECUTION_ID_, ACTIVITY_ID_, PROC_INST_ID_, PROC_DEF_ID_,
               CAUSE_INCIDENT_ID_, ROOT_CAUSE_INCIDENT_ID_, CONFIGURATION_)
          select 'inc-'||g, 1, now() - (g || ' seconds')::interval,
                 'WMS returned 503 for reservation '||g, 'failedExternalTask',
                 'child-'||g, 'callWms', 'child-'||g,
                 case when g %% 5 = 0 then 'childFlow:7:d1' else 'childFlow:8:d1' end,
                 'inc-'||g, 'inc-'||g, 'etask-'||g
          from generate_series(1, %d) g
          """.formatted(INCIDENTS));
      statement.execute("""
          insert into ACT_RU_INCIDENT
              (ID_, REV_, INCIDENT_TIMESTAMP_, INCIDENT_MSG_, INCIDENT_TYPE_,
               EXECUTION_ID_, ACTIVITY_ID_, PROC_INST_ID_, PROC_DEF_ID_,
               CAUSE_INCIDENT_ID_, ROOT_CAUSE_INCIDENT_ID_, CONFIGURATION_)
          select 'cpy-'||g, 1, now() - (g || ' seconds')::interval,
                 'WMS returned 503 for reservation '||g, 'failedExternalTask',
                 'root-'||g, 'reserve', 'root-'||g, 'rootFlow:1:d1',
                 'inc-'||g, 'inc-'||g, null
          from generate_series(1, %d) g
          """.formatted(INCIDENTS));
      statement.execute("analyze");
    }
  }

  private static IncidentGroupRepository repositoryOn(Connection connection) {
    EngineSqlSupport sqlSupport = mock(EngineSqlSupport.class);
    when(sqlSupport.table(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class));
    return new IncidentGroupRepository(
        new NamedParameterJdbcTemplate(
            new SingleConnectionDataSource(connection, true)),
        sqlSupport);
  }

  /** The exact SQL the repository builds, captured through a recording template. */
  private static String captureGroupsSql() {
    SqlRecorder recorder = new SqlRecorder();
    recorder.repository().groups("rootFlow", null, null, null, null, null);
    return recorder.sql;
  }

  private static String captureRetrySql(IncidentGroupRetryRequest selector) {
    SqlRecorder recorder = new SqlRecorder();
    recorder.repository().retryConfigurations(selector);
    return recorder.sql;
  }

  private static final class SqlRecorder {
    private String sql;

    private IncidentGroupRepository repository() {
      NamedParameterJdbcTemplate template = mock(NamedParameterJdbcTemplate.class,
          invocation -> {
            if (invocation.getArguments().length > 0
                && invocation.getArgument(0) instanceof String statement) {
              sql = statement;
            }
            return List.of();
          });
      EngineSqlSupport sqlSupport = mock(EngineSqlSupport.class);
      when(sqlSupport.table(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0, String.class));
      return new IncidentGroupRepository(template, sqlSupport);
    }
  }

  private void explain(Connection connection, String title, String namedSql,
      MapSqlParameterSource params) throws Exception {
    ParsedSql parsed = NamedParameterUtils.parseSqlStatement(namedSql);
    String jdbcSql = NamedParameterUtils.substituteNamedParameters(parsed, params);
    Object[] values = NamedParameterUtils.buildValueArray(parsed, params, null);

    try (PreparedStatement statement =
        connection.prepareStatement("explain (analyze, buffers) " + jdbcSql)) {
      for (int i = 0; i < values.length; i++) {
        statement.setObject(i + 1, values[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        StringBuilder plan = new StringBuilder();
        while (resultSet.next()) {
          plan.append(resultSet.getString(1)).append('\n');
        }
        report.append("=== ").append(title).append(" ===\n")
            .append(namedSql).append('\n')
            .append(params.getValues().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", "-- parameters: ", "\n\n")))
            .append(plan).append('\n');
      }
    }
  }
}
