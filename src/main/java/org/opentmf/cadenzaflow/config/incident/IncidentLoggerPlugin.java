package org.opentmf.cadenzaflow.config.incident;

import java.util.Arrays;
import org.cadenzaflow.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * @author Cezmi Aslan
 * @author Gokhan Demir
 */
@Configuration
public class IncidentLoggerPlugin extends AbstractProcessEnginePlugin {

  private static final Logger log = LoggerFactory.getLogger(IncidentLoggerPlugin.class);

  @Override
  public void preInit(ProcessEngineConfigurationImpl engineConfig) {
    log.info("Initializing CadenzaFlow Incident Logger.");
    engineConfig.setCustomIncidentHandlers(
        Arrays.asList(
            new IncidentLogger(Incident.FAILED_JOB_HANDLER_TYPE),
            new IncidentLogger(Incident.EXTERNAL_TASK_HANDLER_TYPE)));
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    log.info("CadenzaFlow Incident Logger initialization completed.");
  }
}
