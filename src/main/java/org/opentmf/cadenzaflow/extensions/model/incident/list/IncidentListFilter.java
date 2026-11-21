package org.opentmf.cadenzaflow.extensions.model.incident.list;

import java.util.Date;
import java.util.List;

/**
 * The optional filters of the incident list, each mapping 1:1 onto the engine's stock
 * {@code IncidentQuery}. {@code *In} fields carry the already-split comma lists.
 * {@code rootCauseOnly} narrows the list to one row per chain, the originating incident
 * (CF-INCIDENT-LIST-ONE-ROW-PER-CHAIN-1); false is the stock list.
 * {@code rootProcessDefinitionKeyIn} scopes those chains by the definition key of their ROOT
 * process instance, the top of the call tree, wherever in it the failure was raised; it has no
 * stock counterpart and is accepted only with {@code rootCauseOnly}
 * (CF-THE-ONE-ROW-PER-CHAIN-LIST-CAN-BE-SCOPED-BY-THE-CHAINS-ROOT-PROCESS-1).
 *
 * @author Cezmi Aslan
 */
public record IncidentListFilter(
    String incidentType,
    List<String> processDefinitionKeyIn,
    String processDefinitionId,
    String processInstanceId,
    String executionId,
    String activityId,
    String failedActivityId,
    String incidentMessageLike,
    Date incidentTimestampAfter,
    Date incidentTimestampBefore,
    List<String> tenantIdIn,
    List<String> jobDefinitionIdIn,
    String rootCauseIncidentId,
    boolean rootCauseOnly,
    List<String> rootProcessDefinitionKeyIn) {

  /** The filter without a root-process scope. */
  @SuppressWarnings("java:S107") // one argument per list filter, as the canonical constructor
  public IncidentListFilter(String incidentType, List<String> processDefinitionKeyIn,
      String processDefinitionId, String processInstanceId, String executionId,
      String activityId, String failedActivityId, String incidentMessageLike,
      Date incidentTimestampAfter, Date incidentTimestampBefore, List<String> tenantIdIn,
      List<String> jobDefinitionIdIn, String rootCauseIncidentId, boolean rootCauseOnly) {
    this(incidentType, processDefinitionKeyIn, processDefinitionId, processInstanceId,
        executionId, activityId, failedActivityId, incidentMessageLike, incidentTimestampAfter,
        incidentTimestampBefore, tenantIdIn, jobDefinitionIdIn, rootCauseIncidentId,
        rootCauseOnly, List.of());
  }
}
