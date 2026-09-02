package org.opentmf.cadenzaflow.extensions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.cadenzaflow.bpm.engine.runtime.IncidentQuery;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.springframework.data.domain.Sort;

/**
 * @author Cezmi Aslan
 */
class IncidentSortMappingTests {

  /** Every allow-listed name (config-cadenzaflow.yml) and the orderBy it must invoke. */
  private static final Map<String, Consumer<IncidentQuery>> EXPECTED = Map.ofEntries(
      Map.entry("id", q -> q.orderByIncidentId()),
      Map.entry("incidentTimestamp", q -> q.orderByIncidentTimestamp()),
      Map.entry("incidentType", q -> q.orderByIncidentType()),
      Map.entry("incidentMessage", q -> q.orderByIncidentMessage()),
      Map.entry("activityId", q -> q.orderByActivityId()),
      Map.entry("processInstanceId", q -> q.orderByProcessInstanceId()),
      Map.entry("processDefinitionId", q -> q.orderByProcessDefinitionId()),
      Map.entry("executionId", q -> q.orderByExecutionId()),
      Map.entry("causeIncidentId", q -> q.orderByCauseIncidentId()),
      Map.entry("rootCauseIncidentId", q -> q.orderByRootCauseIncidentId()),
      Map.entry("configuration", q -> q.orderByConfiguration()),
      Map.entry("tenantId", q -> q.orderByTenantId()));

  @Test
  void everyAllowlistedNameMapsToItsEngineOrdering() {
    EXPECTED.forEach((name, verification) -> {
      IncidentQuery query = mock(IncidentQuery.class, RETURNS_SELF);
      IncidentSortMapping.apply(query, Sort.by(Sort.Direction.ASC, name));
      InOrder inOrder = inOrder(query);
      verification.accept(inOrder.verify(query));
      inOrder.verify(query).asc();
    });
  }

  @Test
  void directionIsAppliedPerField() {
    IncidentQuery query = mock(IncidentQuery.class, RETURNS_SELF);
    IncidentSortMapping.apply(query, Sort.by(Sort.Order.desc("incidentTimestamp")));
    InOrder inOrder = inOrder(query);
    inOrder.verify(query).orderByIncidentTimestamp();
    inOrder.verify(query).desc();
  }

  @Test
  void multiFieldSortsChainInOrder() {
    IncidentQuery query = mock(IncidentQuery.class, RETURNS_SELF);
    IncidentSortMapping.apply(query, Sort.by(
        Sort.Order.desc("incidentTimestamp"), Sort.Order.asc("activityId")));
    InOrder inOrder = inOrder(query);
    inOrder.verify(query).orderByIncidentTimestamp();
    inOrder.verify(query).desc();
    inOrder.verify(query).orderByActivityId();
    inOrder.verify(query).asc();
  }

  @Test
  void unknownFieldThrowsNamingTheDrift() {
    // The allowlist rejects unknown names with a 400 first; this guards the two lists
    // drifting apart (a name allow-listed but never mapped).
    IncidentQuery query = mock(IncidentQuery.class, RETURNS_SELF);
    List<Sort.Order> orders = List.of(Sort.Order.asc("failedActivityId"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> IncidentSortMapping.apply(query, Sort.by(orders)))
        .withMessageContaining("failedActivityId");
  }

  @Test
  void mappingCoversTheWholeShippedAllowlist() {
    assertThat(EXPECTED).hasSize(12);
    assertThat(IncidentSortMapping.sortableFields()).isEqualTo(EXPECTED.keySet());
  }

  @Test
  void everySortKeyIsAnIncidentRowAttribute() {
    // TMF-630 sorts by the response resource's attribute names: the sort vocabulary's
    // single point of truth is OUR row model, never another library's constants.
    var rowAttributes = java.util.Arrays.stream(IncidentRow.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(java.util.stream.Collectors.toSet());
    assertThat(rowAttributes).containsAll(IncidentSortMapping.sortableFields());
  }

  @Test
  void engineSortCapabilitiesAreFullyExposed() throws Exception {
    // Tripwire, not aliasing: the engine's sort vocabulary is PRIVATE in
    // IncidentQueryDto, and our published names deliberately differ (TMF's `id` vs the
    // engine's `incidentId`). Read it reflectively so an engine upgrade that adds,
    // renames or removes a sort capability fails THIS build visibly instead of
    // silently drifting apart from our mapping.
    Class<?> dto = Class.forName("org.cadenzaflow.bpm.engine.rest.dto.runtime.IncidentQueryDto");
    var field = dto.getDeclaredField("VALID_SORT_BY_VALUES");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var engineNames = new java.util.HashSet<>((java.util.List<String>) field.get(null));

    var oursInEngineTerms = IncidentSortMapping.sortableFields().stream()
        .map(name -> name.equals("id") ? "incidentId" : name)
        .collect(java.util.stream.Collectors.toSet());
    assertThat(oursInEngineTerms).isEqualTo(engineNames);
  }
}
