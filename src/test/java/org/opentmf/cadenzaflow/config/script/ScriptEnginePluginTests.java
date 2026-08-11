package org.opentmf.cadenzaflow.config.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.history.HistoricVariableInstance;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.runtime.ProcessInstance;
import org.cadenzaflow.bpm.model.bpmn.Bpmn;
import org.cadenzaflow.bpm.model.bpmn.BpmnModelInstance;
import org.cadenzaflow.spin.plugin.impl.SpinProcessEnginePlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Leak-regression test for the GraalJS polyglot-context leak: runs JavaScript script tasks (using
 * both Spin's {@code S(...)} environment function and {@code execution.setVariable} host interop)
 * through a real process engine and asserts that every created context is closed again.
 */
class ScriptEnginePluginTests {

  private static final int INSTANCE_COUNT = 500;

  private static SimpleMeterRegistry meterRegistry;
  private static ScriptEnginePlugin plugin;
  private static ProcessEngine processEngine;

  @BeforeAll
  static void buildEngineAndDeploy() {
    meterRegistry = new SimpleMeterRegistry();
    plugin = new ScriptEnginePlugin(meterRegistry);
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEnginePlugins(List.of(plugin, new SpinProcessEnginePlugin()));
    configuration.setJobExecutorActivate(false);
    configuration.setJdbcUrl("jdbc:h2:mem:graaljs-leak-test;DB_CLOSE_DELAY=-1");
    processEngine = configuration.buildProcessEngine();

    BpmnModelInstance model =
        Bpmn.createExecutableProcess("graalJsLeakFix")
            .camundaHistoryTimeToLive(180)
            .startEvent()
            .scriptTask("jsTask")
            .scriptFormat("javascript")
            .scriptText(
                """
                var parsed = S('{"base": 40}');
                execution.setVariable('fromSpin', parsed.prop('base').numberValue() + input);
                input + 1;
                """)
            .camundaResultVariable("result")
            .scriptTask("juelTask")
            .scriptFormat("juel")
            .scriptText("${input + 2}")
            .camundaResultVariable("juelResult")
            .endEvent()
            .done();
    processEngine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("graalJsLeakFix.bpmn", model)
        .deploy();
  }

  @AfterAll
  static void closeEngine() {
    if (processEngine != null) {
      processEngine.close();
    }
    if (plugin != null) {
      plugin.shutdown();
    }
  }

  @Test
  void javascriptScriptTasksDoNotLeakPolyglotContexts() {
    long usedBefore = usedMemoryAfterGc();

    for (int i = 0; i < INSTANCE_COUNT; i++) {
      ProcessInstance instance =
          processEngine
              .getRuntimeService()
              .startProcessInstanceByKey("graalJsLeakFix", Map.of("input", i));
      if (i % 100 == 0 || i == INSTANCE_COUNT - 1) {
        assertEquals(i + 1, ((Number) historicVariable(instance.getId(), "result")).intValue());
        assertEquals(40 + i, ((Number) historicVariable(instance.getId(), "fromSpin")).intValue());
        assertEquals(i + 2, ((Number) historicVariable(instance.getId(), "juelResult")).intValue());
      }
    }

    double created =
        meterRegistry.get(ClosingGraalJsScriptEngine.CONTEXTS_CREATED_METRIC).counter().count();
    double closed =
        meterRegistry.get(ClosingGraalJsScriptEngine.CONTEXTS_CLOSED_METRIC).counter().count();
    assertTrue(created >= INSTANCE_COUNT, "expected at least one context per instance");
    assertEquals(created, closed, "every created polyglot context must be closed");

    // coarse safety net; the counters above are the precise leak signal
    long usedAfter = usedMemoryAfterGc();
    assertTrue(
        usedAfter - usedBefore < 100 * 1024 * 1024,
        "retained heap grew by " + ((usedAfter - usedBefore) >> 20) + " MiB");
  }

  private static Object historicVariable(String processInstanceId, String name) {
    HistoricVariableInstance variable =
        processEngine
            .getHistoryService()
            .createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName(name)
            .singleResult();
    return variable == null ? null : variable.getValue();
  }

  private static long usedMemoryAfterGc() {
    // a cleared WeakReference proves a full collection cycle ran, without sleeping
    WeakReference<Object> collected = new WeakReference<>(new Object());
    while (collected.get() != null) {
      System.gc();
    }
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
