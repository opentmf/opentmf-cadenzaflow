package org.opentmf.cadenzaflow.extensions.repository;

import java.util.Date;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupAssembler;

/**
 * One row of the grouped-incident select: one (definition version, activity, incident
 * type, tenant, caller) combination with its counts — exactly one report group, turned
 * into one by {@link IncidentGroupAssembler}.
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
