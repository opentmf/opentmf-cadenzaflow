package org.opentmf.cadenzaflow.extensions.model.incident.list;

import java.util.Date;

/**
 * One incident of the list: the engine's fields plus the display enrichment the stock
 * query cannot give (definition key/name/version, activity name) and the
 * {@code originating} flag — {@code false} marks a copy the engine propagated into an
 * ancestor instance, which cannot be retried and vanishes with its originating
 * incident.
 *
 * @author Cezmi Aslan
 */
public record IncidentRow(
    String id,
    String href,
    String incidentType,
    Date incidentTimestamp,
    String incidentMessage,
    String activityId,
    String activityName,
    String failedActivityId,
    String processDefinitionId,
    String processDefinitionKey,
    String processDefinitionName,
    Integer processDefinitionVersion,
    String processInstanceId,
    String executionId,
    String causeIncidentId,
    String rootCauseIncidentId,
    boolean originating,
    String configuration,
    String jobDefinitionId,
    String tenantId,
    String annotation) {}
