package org.opentmf.cadenzaflow.extensions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.model.incident.group.CalledFrom;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroup;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupSelector;
import org.opentmf.cadenzaflow.extensions.repository.ActivityNameLookup;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRow;

/**
 * @author Cezmi Aslan
 */
class IncidentGroupRollupTests {

  private static final Date EARLY = Date.from(Instant.parse("2026-08-31T22:10:04Z"));
  private static final Date LATE = Date.from(Instant.parse("2026-09-01T07:58:41Z"));

  private ActivityNameLookup activityNameLookup;
  private IncidentGroupRollup rollup;

  @BeforeEach
  void setUp() {
    activityNameLookup = mock(ActivityNameLookup.class);
    rollup = new IncidentGroupRollup(activityNameLookup);
  }

  private static IncidentGroupRow row(int version, String defName, long incidents,
      Date oldest, Date newest, String sampleMessage) {
    return new IncidentGroupRow("orderFulfilment", "reserveStock:" + version + ":id",
        "reserveStock", defName, version, "callWms", "failedExternalTask", null,
        "orderFulfilment", "reserve", incidents, incidents, oldest, newest, sampleMessage);
  }

  @Test
  void mergeTheVersionsOfOneDefinitionKeyIntoOneGroup() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.of(new ActivityNameLookup.ActivityInfo("Call WMS", "serviceTask")));
    // Version order in the input is deliberately newest-first: the rollup must sort,
    // not trust the arrival order.
    List<IncidentGroup> groups = rollup.rollUp(List.of(
        row(8, "Reserve stock v8", 1000, LATE, LATE, "newest message"),
        row(7, "Reserve stock", 842, EARLY, EARLY, "older message")), null, null);

    assertThat(groups).hasSize(1);
    IncidentGroup group = groups.get(0);
    assertThat(group.rootProcessDefinitionKey()).isEqualTo("orderFulfilment");
    assertThat(group.processDefinitionKey()).isEqualTo("reserveStock");
    assertThat(group.processDefinitionVersions()).containsExactly(7, 8);
    assertThat(group.processDefinitionName()).isEqualTo("Reserve stock v8");
    assertThat(group.activityName()).isEqualTo("Call WMS");
    assertThat(group.activityType()).isEqualTo("serviceTask");
    assertThat(group.incidentCount()).isEqualTo(1842);
    assertThat(group.processInstanceCount()).isEqualTo(1842);
    assertThat(group.oldestIncident()).isEqualTo(EARLY);
    assertThat(group.newestIncident()).isEqualTo(LATE);
    assertThat(group.sampleMessage()).isEqualTo("newest message");
    assertThat(group.calledFrom()).isEqualTo(new CalledFrom("orderFulfilment", "reserve"));
    // calledFrom is part of the group key, so the selector must carry it: without it a
    // retry would also hit the same child called from another call activity.
    assertThat(group.selector()).isEqualTo(new IncidentGroupSelector(
        "orderFulfilment", "reserveStock", "callWms", "failedExternalTask", null,
        new CalledFrom("orderFulfilment", "reserve"), null, null));
  }

  @Test
  void selectorEchoesTheQueryWindowVerbatim() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups = rollup.rollUp(
        List.of(row(8, null, 3, EARLY, LATE, "boom")),
        "2026-09-01T14:00:00.000+0000", "2026-09-02T14:00:00.000+0000");

    IncidentGroupSelector selector = groups.get(0).selector();
    assertThat(selector.incidentTimestampAfter()).isEqualTo("2026-09-01T14:00:00.000+0000");
    assertThat(selector.incidentTimestampBefore()).isEqualTo("2026-09-02T14:00:00.000+0000");
  }

  @Test
  void leaveNamesNullWhenTheActivityIsNoLongerInTheModel() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups =
        rollup.rollUp(List.of(row(8, null, 3, EARLY, LATE, "boom")), null, null);

    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).activityName()).isNull();
    assertThat(groups.get(0).activityType()).isNull();
    assertThat(groups.get(0).processDefinitionName()).isNull();
  }

  @Test
  void leaveCalledFromNullForAnIncidentInTheRootBpmnItself() {
    when(activityNameLookup.activity("orderFulfilment:1:id", "chargeCard"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups = rollup.rollUp(List.of(new IncidentGroupRow(
        "orderFulfilment", "orderFulfilment:1:id", "orderFulfilment", "Order fulfilment", 1,
        "chargeCard", "failedJob", null, null, null, 5, 5, EARLY, LATE, "declined")),
        null, null);

    assertThat(groups.get(0).calledFrom()).isNull();
    assertThat(groups.get(0).selector().calledFrom()).isNull();
  }

  @Test
  void orderGroupsByIncidentCountDescending() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.empty());
    when(activityNameLookup.activity("orderFulfilment:1:id", "chargeCard"))
        .thenReturn(Optional.empty());
    IncidentGroupRow small = new IncidentGroupRow(
        "orderFulfilment", "orderFulfilment:1:id", "orderFulfilment", null, 1,
        "chargeCard", "failedJob", null, null, null, 2, 2, EARLY, LATE, "declined");
    // Two versions of the big group arrive with per-version counts BELOW the small
    // group's count: the ordering must apply after the merge, not per row.
    List<IncidentGroup> groups = rollup.rollUp(List.of(
        small,
        row(7, null, 1, EARLY, EARLY, "older"),
        row(8, null, 2, LATE, LATE, "newer")), null, null);

    assertThat(groups).extracting(IncidentGroup::incidentCount).containsExactly(3L, 2L);
  }

  @Test
  void answerAnEmptyListForNoRows() {
    assertThat(rollup.rollUp(List.of(), null, null)).isEmpty();
  }
}
