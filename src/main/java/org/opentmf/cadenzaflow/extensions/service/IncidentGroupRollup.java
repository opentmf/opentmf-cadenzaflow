package org.opentmf.cadenzaflow.extensions.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.opentmf.cadenzaflow.extensions.model.incident.group.CalledFrom;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroup;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupSelector;
import org.opentmf.cadenzaflow.extensions.repository.ActivityNameLookup;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRow;
import org.springframework.stereotype.Component;

/**
 * Rolls the per-definition-VERSION rows of the grouped select up to definition-KEY
 * level and attaches display names. The SQL groups on the definition id so versions
 * come out separately; here the few hundred resulting rows are merged, which keeps the
 * version arithmetic out of the database and the individual incidents out of Java.
 *
 * @author Cezmi Aslan
 */
@Component
public class IncidentGroupRollup {

  /** Rows describing the same operational problem, regardless of definition version. */
  private record GroupKey(
      String processDefinitionKey, String activityId, String incidentType, String tenantId,
      String calledFromProcessDefinitionKey, String callActivityId) {

    static GroupKey of(IncidentGroupRow row) {
      return new GroupKey(row.processDefinitionKey(), row.activityId(), row.incidentType(),
          row.tenantId(), row.calledFromProcessDefinitionKey(), row.callActivityId());
    }
  }

  private final ActivityNameLookup activityNameLookup;

  public IncidentGroupRollup(ActivityNameLookup activityNameLookup) {
    this.activityNameLookup = activityNameLookup;
  }

  /**
   * The {@code incidentTimestampAfter}/{@code incidentTimestampBefore} strings are the
   * report request's own raw values; they are echoed verbatim into every selector so a
   * retry posted from a time-filtered view is scoped to the same window.
   */
  public List<IncidentGroup> rollUp(List<IncidentGroupRow> rows,
      String incidentTimestampAfter, String incidentTimestampBefore) {
    Map<GroupKey, List<IncidentGroupRow>> byGroup = new LinkedHashMap<>();
    for (IncidentGroupRow row : rows) {
      byGroup.computeIfAbsent(GroupKey.of(row), _ -> new ArrayList<>()).add(row);
    }
    return byGroup.entrySet().stream()
        .map(entry -> merge(entry.getKey(), entry.getValue(),
            incidentTimestampAfter, incidentTimestampBefore))
        .sorted(Comparator.comparingLong(IncidentGroup::incidentCount).reversed())
        .toList();
  }

  private IncidentGroup merge(GroupKey key, List<IncidentGroupRow> versions,
      String incidentTimestampAfter, String incidentTimestampBefore) {
    // Names come from the NEWEST version present: that is the model an operator will
    // open, and the one whose wording is current.
    IncidentGroupRow newest = versions.stream()
        .max(Comparator.comparingInt(IncidentGroupRow::processDefinitionVersion))
        .orElseThrow();
    ActivityNameLookup.ActivityInfo activity = activityNameLookup
        .activity(newest.processDefinitionId(), key.activityId())
        .orElse(new ActivityNameLookup.ActivityInfo(null, null));

    CalledFrom calledFrom = key.calledFromProcessDefinitionKey() == null
        ? null
        : new CalledFrom(key.calledFromProcessDefinitionKey(), key.callActivityId());

    return new IncidentGroup(
        newest.rootProcessDefinitionKey(),
        key.processDefinitionKey(),
        newest.processDefinitionName(),
        versions.stream()
            .map(IncidentGroupRow::processDefinitionVersion).sorted().toList(),
        key.activityId(),
        activity.name(),
        activity.type(),
        key.incidentType(),
        key.tenantId(),
        calledFrom,
        versions.stream().mapToLong(IncidentGroupRow::incidentCount).sum(),
        // A process instance runs exactly one definition version, so the per-version
        // distinct counts are disjoint and their sum is exact.
        versions.stream().mapToLong(IncidentGroupRow::processInstanceCount).sum(),
        versions.stream().map(IncidentGroupRow::oldestIncident)
            .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
        versions.stream().map(IncidentGroupRow::newestIncident)
            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
        newest.sampleMessage(),
        new IncidentGroupSelector(
            newest.rootProcessDefinitionKey(), key.processDefinitionKey(), key.activityId(),
            key.incidentType(), key.tenantId(),
            incidentTimestampAfter, incidentTimestampBefore));
  }
}
