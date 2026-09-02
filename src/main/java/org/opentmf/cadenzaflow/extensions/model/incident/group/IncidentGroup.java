package org.opentmf.cadenzaflow.extensions.model.incident.group;

import java.util.Date;
import java.util.List;

/**
 * One line of the grouped incident report: all originating incidents of one activity ×
 * incident type × tenant in one called (or root) BPMN, across every deployed version,
 * scoped to one root definition's call tree.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroup(
    String rootProcessDefinitionKey,
    String processDefinitionKey,
    String processDefinitionName,
    List<Integer> processDefinitionVersions,
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
