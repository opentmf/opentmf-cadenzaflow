package org.opentmf.cadenzaflow.extensions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.cadenzaflow.bpm.engine.runtime.IncidentQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.opentmf.cadenzaflow.extensions.repository.ActivityNameLookup;
import org.opentmf.cadenzaflow.extensions.repository.ProcessDefinitionLookup;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

/**
 * @author Cezmi Aslan
 */
class IncidentListServiceTests {

  private static final IncidentListFilter EMPTY_FILTER = new IncidentListFilter(
      null, null, null, null, null, null, null, null, null, null, null, null, null);

  private IncidentQuery query;
  private ProcessDefinitionLookup definitionLookup;
  private ActivityNameLookup activityNameLookup;
  private IncidentListService service;

  @BeforeEach
  void setUp() {
    query = mock(IncidentQuery.class, RETURNS_SELF);
    when(query.count()).thenReturn(0L);
    when(query.listPage(anyInt(), anyInt())).thenReturn(List.of());
    RuntimeService runtimeService = mock(RuntimeService.class);
    when(runtimeService.createIncidentQuery()).thenReturn(query);
    definitionLookup = mock(ProcessDefinitionLookup.class);
    activityNameLookup = mock(ActivityNameLookup.class);
    service = new IncidentListService(runtimeService, definitionLookup, activityNameLookup);
  }

  private Page<IncidentRow> page(IncidentListFilter filter) {
    return service.page(filter, new OffsetLimitPageRequest(20, 10, Sort.unsorted()),
        Sort.by(Sort.Direction.DESC, "incidentTimestamp"), "/cadenzaflow/v1/engine-rest/");
  }

  @Test
  void everyGivenFilterReachesTheEngineQueryAndAbsentOnesDoNot() {
    Date after = new Date(1000);
    Date before = new Date(2000);
    page(new IncidentListFilter("failedExternalTask", List.of("a", "b"), "def-1", "pi-1",
        "exec-1", "act-1", "failed-1", "%WMS%", after, before, List.of("t1"),
        List.of("jd1"), "root-1"));

    verify(query).incidentType("failedExternalTask");
    verify(query).processDefinitionKeyIn("a", "b");
    verify(query).processDefinitionId("def-1");
    verify(query).processInstanceId("pi-1");
    verify(query).executionId("exec-1");
    verify(query).activityId("act-1");
    verify(query).failedActivityId("failed-1");
    verify(query).incidentMessageLike("%WMS%");
    // after is INCLUSIVE in the endpoint contract; the stock query is strict '>',
    // so the service must shift the bound by one millisecond.
    verify(query).incidentTimestampAfter(new Date(after.getTime() - 1));
    verify(query).incidentTimestampBefore(before);
    verify(query).tenantIdIn("t1");
    verify(query).jobDefinitionIdIn("jd1");
    verify(query).rootCauseIncidentId("root-1");
  }

  @Test
  void anEmptyFilterTouchesNoFilterMethod() {
    page(EMPTY_FILTER);
    // Only the sort, the count and the page read may reach the query.
    verify(query).orderByIncidentTimestamp();
    verify(query).desc();
    verify(query).count();
    verify(query).listPage(20, 10);
    verifyNoMoreInteractions(query);
  }

  @Test
  void offsetAndLimitComeFromThePageableAndTotalFromTheCount() {
    when(query.count()).thenReturn(42L);
    Page<IncidentRow> result = page(EMPTY_FILTER);
    verify(query).listPage(20, 10);
    assertThat(result.getTotalElements()).isEqualTo(42L);
  }

  @Test
  void rowsAreEnrichedWithNamesHrefAndTheOriginatingFlag() {
    Incident originating = incident("inc-1", "inc-1");
    Incident copy = incident("cpy-1", "inc-1");
    when(query.listPage(anyInt(), anyInt())).thenReturn(List.of(originating, copy));
    ProcessDefinition definition = mock(ProcessDefinition.class);
    when(definition.getKey()).thenReturn("childFlow");
    when(definition.getName()).thenReturn("Child flow");
    when(definition.getVersion()).thenReturn(8);
    when(definitionLookup.byId("childFlow:8:x")).thenReturn(Optional.of(definition));
    when(activityNameLookup.activity("childFlow:8:x", "callWms"))
        .thenReturn(Optional.of(new ActivityNameLookup.ActivityInfo("Call WMS", "serviceTask")));

    List<IncidentRow> rows = page(EMPTY_FILTER).getContent();

    assertThat(rows).hasSize(2);
    // Both rows share one definition: the engine-command lookup must run once per
    // DISTINCT definition per page, not once per row.
    verify(definitionLookup, times(1)).byId("childFlow:8:x");
    IncidentRow first = rows.get(0);
    assertThat(first.href()).isEqualTo("/cadenzaflow/v1/engine-rest/incident/inc-1");
    assertThat(first.processDefinitionKey()).isEqualTo("childFlow");
    assertThat(first.processDefinitionName()).isEqualTo("Child flow");
    assertThat(first.processDefinitionVersion()).isEqualTo(8);
    assertThat(first.activityName()).isEqualTo("Call WMS");
    assertThat(first.originating()).isTrue();
    assertThat(rows.get(1).originating()).isFalse();
  }

  @Test
  void aVanishedDefinitionYieldsNullNamesNotAnError() {
    // Built before the stubbing: a mock created inside a thenReturn argument nests
    // one stubbing in another, which Mockito rejects as UnfinishedStubbing.
    Incident orphaned = incident("inc-1", "inc-1");
    when(query.listPage(anyInt(), anyInt())).thenReturn(List.of(orphaned));
    when(definitionLookup.byId("childFlow:8:x")).thenReturn(Optional.empty());
    when(activityNameLookup.activity("childFlow:8:x", "callWms"))
        .thenReturn(Optional.empty());

    IncidentRow row = page(EMPTY_FILTER).getContent().get(0);
    assertThat(row.processDefinitionKey()).isNull();
    assertThat(row.processDefinitionName()).isNull();
    assertThat(row.processDefinitionVersion()).isNull();
    assertThat(row.activityName()).isNull();
  }

  @Test
  void aNullActivityIdYieldsNoNameAndNoLookup() {
    Incident jobless = incident("inc-1", "inc-1");
    when(jobless.getActivityId()).thenReturn(null);
    when(query.listPage(anyInt(), anyInt())).thenReturn(List.of(jobless));
    when(definitionLookup.byId("childFlow:8:x")).thenReturn(Optional.empty());

    assertThat(page(EMPTY_FILTER).getContent().get(0).activityName()).isNull();
    verifyNoMoreInteractions(activityNameLookup);
  }

  private static Incident incident(String id, String rootCauseId) {
    Incident incident = mock(Incident.class);
    when(incident.getId()).thenReturn(id);
    when(incident.getRootCauseIncidentId()).thenReturn(rootCauseId);
    when(incident.getProcessDefinitionId()).thenReturn("childFlow:8:x");
    when(incident.getActivityId()).thenReturn("callWms");
    when(incident.getIncidentType()).thenReturn("failedExternalTask");
    return incident;
  }
}
