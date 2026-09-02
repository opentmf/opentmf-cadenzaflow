package org.opentmf.cadenzaflow.extensions.model.incident.group;

/**
 * Identifies one group from the report, tree scope included — exactly the body
 * {@code POST /engine-rest/extensions/incident/retry} accepts (minus {@code retries}), so a
 * client never assembles it by hand. When the report was queried with a time window,
 * the selector ECHOES that window verbatim (the exact strings the client sent), so a
 * retry posted from a filtered view touches exactly the slice that was displayed —
 * never the whole group. {@code calledFrom} is echoed for the same reason: it is part
 * of the group key, and without it a retry would also hit the sibling group of the same
 * child BPMN called from another call activity under the same root.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroupSelector(
    String rootProcessDefinitionKey,
    String processDefinitionKey,
    String activityId,
    String incidentType,
    String tenantId,
    CalledFrom calledFrom,
    String incidentTimestampAfter,
    String incidentTimestampBefore) {}
