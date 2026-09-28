package org.opentmf.cadenzaflow.extensions.model.incident.group;

import java.util.Date;

/**
 * One line of the grouped incident report: all originating incidents of one called (or
 * root) BPMN VERSION × activity × incident type × tenant × caller, scoped to one root
 * definition's call tree. The root key is not repeated here — it is the report's own
 * required query parameter — but it stays in the {@link #selector()}, which a retry
 * needs to resolve the tree.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroup(
    String processDefinitionKey,
    int processDefinitionVersion,
    String processDefinitionName,
    String activityId,
    String activityName,
    String activityType,
    String incidentType,
    String tenantId,
    CalledFrom calledFrom,
    long incidentCount,
    long processInstanceCount,
    Date oldestIncident,
    Date newestIncident,
    String sampleMessage,
    IncidentGroupSelector selector) {}
