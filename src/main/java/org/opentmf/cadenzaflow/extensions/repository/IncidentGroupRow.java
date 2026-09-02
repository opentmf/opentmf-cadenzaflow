package org.opentmf.cadenzaflow.extensions.repository;

import java.util.Date;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupRollup;

/**
 * One row of the grouped-incident select: one (definition version, activity, incident
 * type, tenant, caller) combination with its counts. Rolled up to definition-key level
 * by {@link IncidentGroupRollup}.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroupRow(
    String rootProcessDefinitionKey,
    String processDefinitionId,
    String processDefinitionKey,
    String processDefinitionName,
    int processDefinitionVersion,
    String activityId,
    String incidentType,
    String tenantId,
    String calledFromProcessDefinitionKey,
    String callActivityId,
    long incidentCount,
    long processInstanceCount,
    Date oldestIncident,
    Date newestIncident,
    String sampleMessage) {}
