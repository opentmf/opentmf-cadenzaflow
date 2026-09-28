package org.opentmf.cadenzaflow.extensions.service;

import java.util.List;
import org.opentmf.cadenzaflow.extensions.model.incident.group.CalledFrom;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroup;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupSelector;
import org.opentmf.cadenzaflow.extensions.repository.ActivityNameLookup;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRow;
import org.springframework.stereotype.Component;

/**
 * Turns the rows of the grouped select into report groups: attaches display names and
 * builds each group's retry selector. One row is one group — the SQL already groups on
 * the definition VERSION, and versions are deliberately NOT merged here: an operator
 * must see how many incidents each version carries (is the fix deployed in v9
 * working, or is v8 still failing?), and a retry posted from a group must touch that
 * version only.
 *
 * @author Cezmi Aslan
 */
@Component
public class IncidentGroupAssembler {

  private final ActivityNameLookup activityNameLookup;

  public IncidentGroupAssembler(ActivityNameLookup activityNameLookup) {
    this.activityNameLookup = activityNameLookup;
  }

  /**
   * The {@code incidentTimestampAfter}/{@code incidentTimestampBefore} strings are the
   * report request's own raw values; they are echoed verbatim into every selector so a
   * retry posted from a time-filtered view is scoped to the same window. Row order —
   * the select's incident-count ordering — is preserved.
   */
  public List<IncidentGroup> assemble(List<IncidentGroupRow> rows,
      String incidentTimestampAfter, String incidentTimestampBefore) {
    return rows.stream()
        .map(row -> assemble(row, incidentTimestampAfter, incidentTimestampBefore))
        .toList();
  }

  private IncidentGroup assemble(IncidentGroupRow row,
      String incidentTimestampAfter, String incidentTimestampBefore) {
    // Names come from the row's OWN version: the element may be named differently, or
    // be gone, in another version of the same key.
    ActivityNameLookup.ActivityInfo activity = activityNameLookup
        .activity(row.processDefinitionId(), row.activityId())
        .orElse(new ActivityNameLookup.ActivityInfo(null, null));

    CalledFrom calledFrom = row.calledFromProcessDefinitionKey() == null
        ? null
        : new CalledFrom(row.calledFromProcessDefinitionKey(), row.callActivityId());

    return new IncidentGroup(
        row.processDefinitionKey(),
        row.processDefinitionVersion(),
        row.processDefinitionName(),
        row.activityId(),
        activity.name(),
        activity.type(),
        row.incidentType(),
        row.tenantId(),
        calledFrom,
        row.incidentCount(),
        row.processInstanceCount(),
        row.oldestIncident(),
        row.newestIncident(),
        row.sampleMessage(),
        new IncidentGroupSelector(
            row.rootProcessDefinitionKey(), row.processDefinitionKey(),
            row.processDefinitionVersion(), row.activityId(), row.incidentType(),
            row.tenantId(), calledFrom, incidentTimestampAfter, incidentTimestampBefore));
  }
}
