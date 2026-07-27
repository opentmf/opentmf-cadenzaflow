package org.opentmf.cadenzaflow.config.incident;

import java.util.List;
import org.cadenzaflow.bpm.engine.impl.context.Context;
import org.cadenzaflow.bpm.engine.impl.incident.DefaultIncidentHandler;
import org.cadenzaflow.bpm.engine.impl.incident.IncidentContext;
import org.cadenzaflow.bpm.engine.impl.incident.IncidentHandler;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Cezmi Aslan
 */
public class IncidentLogger extends DefaultIncidentHandler implements IncidentHandler {

  private static final Logger log = LoggerFactory.getLogger(IncidentLogger.class);

  /**
   * @param type the incident handler type (e.g. {@code failedJob} or {@code failedExternalTask})
   */
  public IncidentLogger(String type) {
    super(type);
  }

  @Override
  public Incident handleIncident(IncidentContext context, String message) {
    // Incidents without an execution (e.g. raised during process instance version
    // migrations) are intentionally not logged - they are not actionable and only
    // produce noise.
    if (context.getExecutionId() == null) {
      return super.handleIncident(context, message);
    }

    try {
      ExecutionEntity execution = Context.getCommandContext().getExecutionManager()
          .findExecutionById(context.getExecutionId());
      List<DeploymentEntity> deployments = Context.getCommandContext().getDeploymentManager()
          .findDeploymentsByIds(execution.getProcessDefinition().getDeploymentId());
      String deploymentName = null;
      if (!deployments.isEmpty()) {
        deploymentName = deployments.get(0).getName();
      }
      log.warn(
          "CadenzaFlow Incident: '{}' --> '{} (version {})' --> '{}'."
              + " '{}', processInstanceId: '{}' and message: '{}'",
          deploymentName,
          execution.getProcessDefinition().getName() != null
              ? execution.getProcessDefinition().getName() : context.getActivityId(),
          execution.getProcessDefinition().getVersion(),
          execution.getActivity().getName(),
          this.getIncidentHandlerType(),
          execution.getProcessInstanceId(),
          message
      );
    } catch (Throwable throwable) {
      log.error(
          "Exception while logging cadenzaflow incident. Please check incidents"
              + " and fix this code error.", throwable);
    }
    return super.handleIncident(context, message);
  }
}
