package org.opentmf.cadenzaflow.config.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
class EngineMetricsBridgeTests {

  private ProcessEngine engine;
  private SimpleMeterRegistry registry;

  @BeforeAll
  void boot() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("metrics-bridge-test");
    configuration.setJdbcUrl("jdbc:h2:mem:metrics-bridge;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    engine = configuration.buildProcessEngine();
    registry = new SimpleMeterRegistry();
    new EngineMetricsBridge(engine, registry);
  }

  @AfterAll
  void shutdown() {
    engine.close();
    registry.close();
  }

  @Test
  void registersEngineSumCountersResolvableAtScrapeTime() {
    var counter = registry.find("cadenzaflow.engine.job.successful").functionCounter();
    assertNotNull(counter);
    assertEquals(0.0, counter.count());
    assertNotNull(registry.find("cadenzaflow.engine.root.process.instance.start")
        .functionCounter());
  }

  @Test
  void registersLiveGauges() {
    var active = registry.find("cadenzaflow.engine.process.instances.active").gauge();
    var incidents = registry.find("cadenzaflow.engine.incidents.open").gauge();
    assertNotNull(active);
    assertNotNull(incidents);
    assertEquals(0.0, active.value());
    assertEquals(0.0, incidents.value());
  }
}
