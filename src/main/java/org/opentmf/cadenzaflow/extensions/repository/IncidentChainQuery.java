package org.opentmf.cadenzaflow.extensions.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.IncidentEntity;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The incident list with ONE row per chain (CF-INCIDENT-LIST-ONE-ROW-PER-CHAIN-1,
 * {@code rootCauseOnly=true}): only the ORIGINATING incident of each chain, the one whose id
 * is its own {@code rootCauseIncidentId}. The copies the engine propagates into each calling
 * instance are left out, so a failure two call activities deep is one row, and offset, limit
 * and the total count count chains. The same predicate the grouped report selects with.
 *
 * <p>The stock {@code IncidentQuery} has no such restriction and the engine has no native
 * incident query, so this reads {@code ACT_RU_INCIDENT} with JDBC, as the grouped report does,
 * and maps each row into the engine's own {@code IncidentEntity}: the list then renders it
 * exactly as it renders a stock query row. Every list filter is expressed here with the stock
 * query's semantics, column for column (pinned by {@code IncidentChainQueryTests} and the IT);
 * the default list - {@code rootCauseOnly} absent or false - does not come through here.</p>
 *
 * <p>Like every endpoint under {@code /engine-rest}, this runs without an authenticated engine
 * user, so the stock query applies no authorization or tenant check either; nor does this.</p>
 *
 * @author Cezmi Aslan
 */
@Component
public class IncidentChainQuery {

  static final String INCIDENT = "ACT_RU_INCIDENT";
  static final String PROCESS_DEFINITION = "ACT_RE_PROCDEF";
  private static final String NEXT_CONDITION = "and    RES.";
  private static final String ID_COLUMN = "ID_";
  private static final String INCIDENT_TYPE = "INCIDENT_TYPE_";
  private static final String ACTIVITY_ID = "ACTIVITY_ID_";
  private static final String PROCESS_INSTANCE_ID = "PROC_INST_ID_";
  private static final String PROCESS_DEFINITION_ID = "PROC_DEF_ID_";
  private static final String EXECUTION_ID = "EXECUTION_ID_";
  private static final String ROOT_CAUSE_INCIDENT_ID = "ROOT_CAUSE_INCIDENT_ID_";
  private static final String TENANT_ID = "TENANT_ID_";

  /** The list's sort vocabulary ({@code IncidentRow} attribute names) to incident columns. */
  private static final Map<String, String> ORDER_COLUMN = Map.ofEntries(
      Map.entry("id", ID_COLUMN),
      Map.entry("incidentTimestamp", "INCIDENT_TIMESTAMP_"),
      Map.entry("incidentType", INCIDENT_TYPE),
      Map.entry("incidentMessage", "INCIDENT_MSG_"),
      Map.entry("activityId", ACTIVITY_ID),
      Map.entry("processInstanceId", PROCESS_INSTANCE_ID),
      Map.entry("processDefinitionId", PROCESS_DEFINITION_ID),
      Map.entry("executionId", EXECUTION_ID),
      Map.entry("causeIncidentId", "CAUSE_INCIDENT_ID_"),
      Map.entry("rootCauseIncidentId", ROOT_CAUSE_INCIDENT_ID),
      Map.entry("configuration", "CONFIGURATION_"),
      Map.entry("tenantId", TENANT_ID));

  private final NamedParameterJdbcTemplate jdbc;
  private final EngineSqlSupport sql;

  public IncidentChainQuery(NamedParameterJdbcTemplate jdbc, EngineSqlSupport sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  /** The sortable field names, for the guard that keeps them equal to the stock mapping's. */
  public static Set<String> sortableFields() {
    return ORDER_COLUMN.keySet();
  }

  /** How many chains match the filter. */
  public long count(IncidentListFilter filter) {
    Where where = where(filter);
    // Built like IncidentGroupRepository's statements: constant text, the validated table
    // prefix and the allowlisted sort columns only; every value is a named parameter.
    StringBuilder select = new StringBuilder("select count(*) from ")
        .append(sql.table(INCIDENT)).append(" RES\n").append(where.sql());
    Long count = jdbc.queryForObject(select.toString(), where.params(), Long.class);
    return count == null ? 0 : count;
  }

  /** One page of the chains' originating incidents, in the list's sort order. */
  public List<Incident> page(IncidentListFilter filter, Sort sort, long offset, int limit) {
    Where where = where(filter);
    Map<String, Object> params = new LinkedHashMap<>(where.params());
    params.put("offset", offset);
    params.put("limit", limit);
    StringBuilder select = new StringBuilder("select RES.* from ")
        .append(sql.table(INCIDENT)).append(" RES\n").append(where.sql()).append(orderBy(sort))
        .append("\noffset :offset rows fetch next :limit rows only");
    return jdbc.query(select.toString(), params, IncidentChainQuery::toIncident);
  }

  /** The WHERE clause and its parameters; each filter with the stock query's semantics. */
  Where where(IncidentListFilter filter) {
    StringBuilder text = new StringBuilder("where  RES.ID_ = RES.ROOT_CAUSE_INCIDENT_ID_\n");
    Map<String, Object> params = new LinkedHashMap<>();
    equalTo(text, params, INCIDENT_TYPE, "incidentType", filter.incidentType());
    if (has(filter.processDefinitionKeyIn())) {
      text.append("and    exists (select 1 from ").append(sql.table(PROCESS_DEFINITION))
          .append(" PD where PD.ID_ = RES.PROC_DEF_ID_ and PD.KEY_ in (:definitionKeys))\n");
      params.put("definitionKeys", filter.processDefinitionKeyIn());
    }
    equalTo(text, params, PROCESS_DEFINITION_ID, "processDefinitionId", filter.processDefinitionId());
    equalTo(text, params, PROCESS_INSTANCE_ID, "processInstanceId", filter.processInstanceId());
    equalTo(text, params, EXECUTION_ID, "executionId", filter.executionId());
    equalTo(text, params, ACTIVITY_ID, "activityId", filter.activityId());
    equalTo(text, params, "FAILED_ACTIVITY_ID_", "failedActivityId", filter.failedActivityId());
    if (filter.incidentMessageLike() != null) {
      text.append(NEXT_CONDITION).append("INCIDENT_MSG_ like :incidentMessageLike\n");
      params.put("incidentMessageLike", filter.incidentMessageLike());
    }
    // The list's window is half-open, after INCLUSIVE: the same bound the stock path reaches
    // with its strict '>' and after - 1 ms.
    time(text, params, ">=", "incidentTimestampAfter", filter.incidentTimestampAfter());
    time(text, params, "<", "incidentTimestampBefore", filter.incidentTimestampBefore());
    in(text, params, TENANT_ID, "tenantIds", filter.tenantIdIn());
    in(text, params, "JOB_DEF_ID_", "jobDefinitionIds", filter.jobDefinitionIdIn());
    equalTo(text, params, ROOT_CAUSE_INCIDENT_ID, "rootCauseIncidentId",
        filter.rootCauseIncidentId());
    return new Where(text.toString(), params);
  }

  /** The sort as an ORDER BY, the id last as the tie-breaker so pages never overlap. */
  static String orderBy(Sort sort) {
    StringBuilder text = new StringBuilder();
    boolean byId = false;
    for (Sort.Order order : sort) {
      String column = ORDER_COLUMN.get(order.getProperty());
      if (column == null) {
        throw new IllegalArgumentException("No incident column for sort field '"
            + order.getProperty() + "' - the sort allowlist and IncidentChainQuery have drifted");
      }
      byId |= ID_COLUMN.equals(column);
      text.append(text.isEmpty() ? "order by " : ", ")
          .append("RES.").append(column).append(order.isAscending() ? " asc" : " desc");
    }
    if (!byId) {
      text.append(text.isEmpty() ? "order by " : ", ").append("RES.ID_ asc");
    }
    return text.toString();
  }

  record Where(String sql, Map<String, Object> params) {}

  private static Incident toIncident(ResultSet rs, int rowNum) throws SQLException {
    IncidentEntity incident = new IncidentEntity();
    incident.setId(rs.getString(ID_COLUMN));
    incident.setRevision(rs.getInt("REV_"));
    incident.setIncidentTimestamp(rs.getTimestamp("INCIDENT_TIMESTAMP_"));
    incident.setIncidentType(rs.getString(INCIDENT_TYPE));
    incident.setIncidentMessage(rs.getString("INCIDENT_MSG_"));
    incident.setExecutionId(rs.getString(EXECUTION_ID));
    incident.setActivityId(rs.getString(ACTIVITY_ID));
    incident.setFailedActivityId(rs.getString("FAILED_ACTIVITY_ID_"));
    incident.setProcessInstanceId(rs.getString(PROCESS_INSTANCE_ID));
    incident.setProcessDefinitionId(rs.getString(PROCESS_DEFINITION_ID));
    incident.setCauseIncidentId(rs.getString("CAUSE_INCIDENT_ID_"));
    incident.setRootCauseIncidentId(rs.getString(ROOT_CAUSE_INCIDENT_ID));
    incident.setConfiguration(rs.getString("CONFIGURATION_"));
    incident.setTenantId(rs.getString(TENANT_ID));
    incident.setJobDefinitionId(rs.getString("JOB_DEF_ID_"));
    incident.setAnnotation(rs.getString("ANNOTATION_"));
    return incident;
  }

  private static void equalTo(StringBuilder text, Map<String, Object> params, String column,
      String name, String value) {
    if (value != null) {
      text.append(NEXT_CONDITION).append(column).append(" = :").append(name).append('\n');
      params.put(name, value);
    }
  }

  private static void in(StringBuilder text, Map<String, Object> params, String column,
      String name, List<String> values) {
    if (has(values)) {
      text.append(NEXT_CONDITION).append(column).append(" in (:").append(name).append(")\n");
      params.put(name, values);
    }
  }

  private static void time(StringBuilder text, Map<String, Object> params, String operator,
      String name, Date value) {
    if (value != null) {
      text.append(NEXT_CONDITION).append("INCIDENT_TIMESTAMP_ ").append(operator)
          .append(" :").append(name).append('\n');
      params.put(name, value);
    }
  }

  private static boolean has(List<String> values) {
    return values != null && !values.isEmpty();
  }
}
