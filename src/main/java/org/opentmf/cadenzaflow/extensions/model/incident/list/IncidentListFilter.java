package org.opentmf.cadenzaflow.extensions.model.incident.list;

import java.util.Date;
import java.util.List;

/**
 * The optional filters of the incident list, each mapping 1:1 onto the engine's stock
 * {@code IncidentQuery}. {@code *In} fields carry the already-split comma lists.
 * {@code rootCauseOnly} narrows the list to one row per chain, the originating incident
 * (CF-INCIDENT-LIST-ONE-ROW-PER-CHAIN-1); false is the stock list.
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
    boolean rootCauseOnly) {}
