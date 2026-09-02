package org.opentmf.cadenzaflow.extensions.model.incident.group;

/**
 * Identifies one group from the report, tree scope included — exactly the body
 * {@code POST /engine-rest/extensions/incident/retry} accepts (minus {@code retries}), so a
 * client never assembles it by hand. When the report was queried with a time window,
 * the selector ECHOES that window verbatim (the exact strings the client sent), so a
 * retry posted from a filtered view touches exactly the slice that was displayed —
 * never the whole group.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroupSelector(
    String rootProcessDefinitionKey,
    String processDefinitionKey,
    String activityId,
    String incidentType,
    String tenantId,
    String incidentTimestampAfter,
    String incidentTimestampBefore) {}
