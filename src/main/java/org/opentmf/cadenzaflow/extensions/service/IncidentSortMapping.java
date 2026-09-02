package org.opentmf.cadenzaflow.extensions.service;

import java.util.Map;
import java.util.function.UnaryOperator;
import org.cadenzaflow.bpm.engine.runtime.IncidentQuery;
import org.springframework.data.domain.Sort;

/**
 * Maps TMF sort field names onto the engine query's {@code orderBy*} methods. The
 * config allowlist (config-cadenzaflow.yml) and this map name the same twelve fields —
 * the allowlist rejects unknown names with a 400 before this class runs, and the
 * defensive throw below covers a drift between the two lists.
 *
 * @author Cezmi Aslan
 */
final class IncidentSortMapping {

  private static final Map<String, UnaryOperator<IncidentQuery>> ORDER_BY = Map.ofEntries(
      Map.entry("id", IncidentQuery::orderByIncidentId),
      Map.entry("incidentTimestamp", IncidentQuery::orderByIncidentTimestamp),
      Map.entry("incidentType", IncidentQuery::orderByIncidentType),
      Map.entry("incidentMessage", IncidentQuery::orderByIncidentMessage),
      Map.entry("activityId", IncidentQuery::orderByActivityId),
      Map.entry("processInstanceId", IncidentQuery::orderByProcessInstanceId),
      Map.entry("processDefinitionId", IncidentQuery::orderByProcessDefinitionId),
      Map.entry("executionId", IncidentQuery::orderByExecutionId),
      Map.entry("causeIncidentId", IncidentQuery::orderByCauseIncidentId),
      Map.entry("rootCauseIncidentId", IncidentQuery::orderByRootCauseIncidentId),
      Map.entry("configuration", IncidentQuery::orderByConfiguration),
      Map.entry("tenantId", IncidentQuery::orderByTenantId));

  private IncidentSortMapping() {}

  /**
   * The sortable field names — exposed for the consistency guards in the tests: every
   * name must be an {@code IncidentRow} attribute (TMF-630 sorts by response attribute
   * names, which is why this vocabulary is OURS, deliberately not aliased to the
   * engine's private sort constants — {@code id} here is {@code incidentId} there),
   * and together they must cover the engine's own sort capabilities so an engine
   * upgrade that adds one fails the build instead of passing silently.
   */
  static java.util.Set<String> sortableFields() {
    return ORDER_BY.keySet();
  }

  /** Applies every order of {@code sort} to the query, in order (multi-field chains). */
  static void apply(IncidentQuery query, Sort sort) {
    for (Sort.Order order : sort) {
      UnaryOperator<IncidentQuery> orderBy = ORDER_BY.get(order.getProperty());
      if (orderBy == null) {
        throw new IllegalArgumentException(
            "No engine ordering for sort field '" + order.getProperty()
                + "' - the sort allowlist and IncidentSortMapping have drifted apart");
      }
      orderBy.apply(query);
      if (order.isAscending()) {
        query.asc();
      } else {
        query.desc();
      }
    }
  }
}
