package org.opentmf.cadenzaflow.config.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.impl.incident.IncidentHandler;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.junit.jupiter.api.Test;

class IncidentLoggerPluginTest {

  @Test
  void registersLoggingHandlersForFailedJobAndExternalTask() {
    var configuration = new StandaloneInMemProcessEngineConfiguration();

    new IncidentLoggerPlugin().preInit(configuration);

    List<IncidentHandler> handlers = configuration.getCustomIncidentHandlers();
    assertEquals(2, handlers.size());
    assertInstanceOf(IncidentLogger.class, handlers.get(0));
    assertInstanceOf(IncidentLogger.class, handlers.get(1));
    assertEquals(Incident.FAILED_JOB_HANDLER_TYPE, handlers.get(0).getIncidentHandlerType());
    assertEquals(Incident.EXTERNAL_TASK_HANDLER_TYPE, handlers.get(1).getIncidentHandlerType());
  }
}
