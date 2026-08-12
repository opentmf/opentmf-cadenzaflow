package org.opentmf.cadenzaflow.config.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.runtime.ProcessInstance;
import org.cadenzaflow.bpm.model.bpmn.Bpmn;
import org.cadenzaflow.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * The fixture deliberately leaves the engine in a NON-EMPTY state - one running instance, one
 * open incident, one reported process-start metric - because every meter here reads zero on an
 * idle engine, and a bridge that reported a constant zero would satisfy assertions written
 * against an idle one.
 *
 * <p>Each test binds its own registry rather than sharing one built in {@code @BeforeAll}: the
 * binding is the behaviour under test, so it has to run inside the test that judges it.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
class EngineMetricsBridgeTests {

  private StandaloneInMemProcessEngineConfiguration configuration;
  private ProcessEngine engine;

  @BeforeAll
  void bootEngineAndCreateSomethingToMeasure() {
    configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("metrics-bridge-test");
    configuration.setJdbcUrl("jdbc:h2:mem:metrics-bridge;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    engine = configuration.buildProcessEngine();

    BpmnModelInstance model =
        Bpmn.createExecutableProcess("metricsBridgeProbe")
            .camundaHistoryTimeToLive(180)
            .startEvent()
            .userTask("wait")
            .endEvent()
            .done();
    engine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("metricsBridgeProbe.bpmn", model)
        .deploy();

    // Waits at the user task, so it stays active for the gauge to find.
    ProcessInstance instance =
        engine.getRuntimeService().startProcessInstanceByKey("metricsBridgeProbe");
    engine.getRuntimeService().createIncident("metrics-bridge-probe", instance.getId(), "probe");

    // Engine metrics are buffered in memory and flushed on the reporter's own schedule, which
    // never fires with the job executor off - so the counters would read zero without this.
    configuration.getDbMetricsReporter().reportNow();
  }

  @AfterAll
  void shutdown() {
    if (engine != null) {
      engine.close();
    }
  }

  private SimpleMeterRegistry boundRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new EngineMetricsBridge(engine).bindTo(registry);
    return registry;
  }

  @Test
  void registersEngineSumCountersResolvableAtScrapeTime() {
    SimpleMeterRegistry registry = boundRegistry();
    var started = registry.find("cadenzaflow.engine.root.process.instance.start")
        .functionCounter();
    assertNotNull(started);
    assertEquals(1.0, started.count());
    assertNotNull(registry.find("cadenzaflow.engine.job.successful").functionCounter());
    registry.close();
  }

  @Test
  void registersLiveGauges() {
    SimpleMeterRegistry registry = boundRegistry();
    var active = registry.find("cadenzaflow.engine.process.instances.active").gauge();
    var incidents = registry.find("cadenzaflow.engine.incidents.open").gauge();
    assertNotNull(active);
    assertNotNull(incidents);
    assertEquals(1.0, active.value());
    assertEquals(1.0, incidents.value());
    registry.close();
  }
}
