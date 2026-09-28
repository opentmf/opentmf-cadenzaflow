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
class IncidentGroupAssemblerTests {

  private static final Date EARLY = Date.from(Instant.parse("2026-08-31T22:10:04Z"));
  private static final Date LATE = Date.from(Instant.parse("2026-09-01T07:58:41Z"));

  private ActivityNameLookup activityNameLookup;
  private IncidentGroupAssembler assembler;

  @BeforeEach
  void setUp() {
    activityNameLookup = mock(ActivityNameLookup.class);
    assembler = new IncidentGroupAssembler(activityNameLookup);
  }

  private static IncidentGroupRow row(int version, String defName, long incidents,
      Date oldest, Date newest, String sampleMessage) {
    return new IncidentGroupRow("orderFulfilment", "reserveStock:" + version + ":id",
        "reserveStock", defName, version, "callWms", "failedExternalTask", null,
        "orderFulfilment", "reserve", incidents, incidents, oldest, newest, sampleMessage);
  }

  @Test
  void keepEveryVersionAsItsOwnGroupWithItsOwnCounts() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms")).thenReturn(
        Optional.of(new ActivityNameLookup.ActivityInfo("Call WMS v8", "serviceTask")));
    when(activityNameLookup.activity("reserveStock:7:id", "callWms")).thenReturn(
        Optional.of(new ActivityNameLookup.ActivityInfo("Call WMS", "serviceTask")));
    List<IncidentGroup> groups = assembler.assemble(List.of(
        row(8, "Reserve stock v8", 1000, LATE, LATE, "newest message"),
        row(7, "Reserve stock", 842, EARLY, EARLY, "older message")), null, null);

    assertThat(groups).hasSize(2);
    IncidentGroup v8 = groups.get(0);
    assertThat(v8.processDefinitionKey()).isEqualTo("reserveStock");
    assertThat(v8.processDefinitionVersion()).isEqualTo(8);
    assertThat(v8.processDefinitionName()).isEqualTo("Reserve stock v8");
    assertThat(v8.activityName()).isEqualTo("Call WMS v8");
    assertThat(v8.activityType()).isEqualTo("serviceTask");
    assertThat(v8.incidentCount()).isEqualTo(1000);
    assertThat(v8.processInstanceCount()).isEqualTo(1000);
    assertThat(v8.oldestIncident()).isEqualTo(LATE);
    assertThat(v8.newestIncident()).isEqualTo(LATE);
    assertThat(v8.sampleMessage()).isEqualTo("newest message");
    assertThat(v8.calledFrom()).isEqualTo(new CalledFrom("orderFulfilment", "reserve"));
    // calledFrom and the version are part of the group key, so the selector must carry
    // both: without them a retry would also hit the sibling groups.
    assertThat(v8.selector()).isEqualTo(new IncidentGroupSelector(
        "orderFulfilment", "reserveStock", 8, "callWms", "failedExternalTask", null,
        new CalledFrom("orderFulfilment", "reserve"), null, null));

    IncidentGroup v7 = groups.get(1);
    assertThat(v7.processDefinitionVersion()).isEqualTo(7);
    assertThat(v7.processDefinitionName()).isEqualTo("Reserve stock");
    assertThat(v7.activityName()).as("names come from the row's own version")
        .isEqualTo("Call WMS");
    assertThat(v7.incidentCount()).isEqualTo(842);
    assertThat(v7.sampleMessage()).isEqualTo("older message");
    assertThat(v7.selector().processDefinitionVersion()).isEqualTo(7);
  }

  @Test
  void selectorEchoesTheQueryWindowVerbatim() {
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups = assembler.assemble(
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
        assembler.assemble(List.of(row(8, null, 3, EARLY, LATE, "boom")), null, null);

    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).activityName()).isNull();
    assertThat(groups.get(0).activityType()).isNull();
    assertThat(groups.get(0).processDefinitionName()).isNull();
  }

  @Test
  void leaveCalledFromNullForAnIncidentInTheRootBpmnItself() {
    when(activityNameLookup.activity("orderFulfilment:1:id", "chargeCard"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups = assembler.assemble(List.of(new IncidentGroupRow(
        "orderFulfilment", "orderFulfilment:1:id", "orderFulfilment", "Order fulfilment", 1,
        "chargeCard", "failedJob", null, null, null, 5, 5, EARLY, LATE, "declined")),
        null, null);

    assertThat(groups.get(0).calledFrom()).isNull();
    assertThat(groups.get(0).selector().calledFrom()).isNull();
  }

  @Test
  void keepTheOrderOfTheSelect() {
    when(activityNameLookup.activity("reserveStock:7:id", "callWms"))
        .thenReturn(Optional.empty());
    when(activityNameLookup.activity("reserveStock:8:id", "callWms"))
        .thenReturn(Optional.empty());
    List<IncidentGroup> groups = assembler.assemble(List.of(
        row(7, null, 5, EARLY, EARLY, "older"),
        row(8, null, 2, LATE, LATE, "newer")), null, null);

    assertThat(groups).extracting(IncidentGroup::processDefinitionVersion)
        .containsExactly(7, 8);
  }

  @Test
  void answerAnEmptyListForNoRows() {
    assertThat(assembler.assemble(List.of(), null, null)).isEmpty();
  }
}
