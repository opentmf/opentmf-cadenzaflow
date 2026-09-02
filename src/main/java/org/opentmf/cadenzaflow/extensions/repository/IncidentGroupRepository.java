package org.opentmf.cadenzaflow.extensions.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two selects behind the grouped report and the group retry. Both share the same
 * spine: incident → its process instance → the instance's root → the root's definition,
 * so one root BPMN's whole call tree is covered by a single indexed join
 * ({@code ACT_RU_EXECUTION.ROOT_PROC_INST_ID_}), with no BPMN parsing and regardless of
 * how deep or expression-valued the call activities are.
 *
 * <p>Both filter {@code i.ID_ = i.ROOT_CAUSE_INCIDENT_ID_}: the engine copies an
 * incident into every ancestor instance, so without it each failure would be counted
 * (and retried) once per tree level. The copies carry no {@code CONFIGURATION_} anyway —
 * only originating incidents are retryable.</p>
 *
 * <p>Aggregation stays in the database on purpose: at DNMS volume the incident table
 * holds hundreds of thousands of rows, while the grouped result is bounded by
 * definitions × activities × types. Java never sees individual incidents here.</p>
 *
 * @author Cezmi Aslan
 */
@Repository
public class IncidentGroupRepository {

  private static final String EXECUTION = "ACT_RU_EXECUTION";
  private static final String PROCESS_DEFINITION = "ACT_RE_PROCDEF";
  private static final String INCIDENT = "ACT_RU_INCIDENT";

  private final NamedParameterJdbcTemplate jdbc;
  private final EngineSqlSupport sql;

  public IncidentGroupRepository(NamedParameterJdbcTemplate jdbc, EngineSqlSupport sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  /**
   * Per-definition-VERSION groups of the active originating incidents in the call tree
   * of {@code rootProcessDefinitionKey}, ordered by incident count descending. The
   * group key is {@code ACTIVITY_ID_}, deliberately not {@code FAILED_ACTIVITY_ID_}:
   * the engine leaves the latter null on external-task incidents, which would collapse
   * every external-task failure into one empty-activity group.
   */
  public List<IncidentGroupRow> groups(
      String rootProcessDefinitionKey, String incidentType, String tenantId,
      Date incidentTimestampAfter, Date incidentTimestampBefore, Integer minIncidents) {
    Map<String, Object> params = new HashMap<>();
    params.put("rootKey", rootProcessDefinitionKey);

    StringBuilder select = new StringBuilder()
        .append("""
            select rd.KEY_  as ROOT_KEY_,
                   pd.ID_   as DEF_ID_, pd.KEY_ as DEF_KEY_, pd.NAME_ as DEF_NAME_,
                   pd.VERSION_ as DEF_VERSION_,
                   i.ACTIVITY_ID_, i.INCIDENT_TYPE_, i.TENANT_ID_,
                   sd.KEY_  as PARENT_KEY_, sup.ACT_ID_ as CALL_ACTIVITY_ID_,
                   count(*)                             as INCIDENTS_,
                   count(distinct i.PROC_INST_ID_)      as INSTANCES_,
                   min(i.INCIDENT_TIMESTAMP_)           as OLDEST_,
                   max(i.INCIDENT_TIMESTAMP_)           as NEWEST_,
                   min(substr(i.INCIDENT_MSG_, 1, 300)) as SAMPLE_MSG_
            """)
        .append(treeJoins())
        .append(callerJoins("left join"))
        .append("""
            where  rd.KEY_ = :rootKey
            and    i.ID_   = i.ROOT_CAUSE_INCIDENT_ID_
            """);
    appendOptionalIncidentFilters(select, params, incidentType, tenantId);
    // Half-open range on the ORIGINATING incident's raise time: after inclusive,
    // before exclusive, so consecutive windows chain without gaps or double counts.
    // (The stock engine query's 'after' is exclusive; these endpoints deliberately
    // standardize on the half-open contract instead.)
    if (incidentTimestampAfter != null) {
      select.append("and    i.INCIDENT_TIMESTAMP_ >= :timestampAfter\n");
      params.put("timestampAfter", incidentTimestampAfter);
    }
    if (incidentTimestampBefore != null) {
      select.append("and    i.INCIDENT_TIMESTAMP_ < :timestampBefore\n");
      params.put("timestampBefore", incidentTimestampBefore);
    }
    select.append("""
        group by rd.KEY_, pd.ID_, pd.KEY_, pd.NAME_, pd.VERSION_,
                 i.ACTIVITY_ID_, i.INCIDENT_TYPE_, i.TENANT_ID_, sd.KEY_, sup.ACT_ID_
        """);
    if (minIncidents != null) {
      select.append("having count(*) >= :minIncidents\n");
      params.put("minIncidents", minIncidents);
    }
    select.append("order by INCIDENTS_ desc");

    return jdbc.query(select.toString(), params, IncidentGroupRepository::mapRow);
  }

  /**
   * The job / external-task ids ({@code CONFIGURATION_}) of one group's originating
   * incidents — the input for the engine's set-retries batches. Same joins and
   * predicates as {@link #groups}, narrowed to the selector; incidents copied into
   * ancestors carry no configuration and are excluded with the originating filter.
   */
  public List<String> retryConfigurations(IncidentGroupRetryRequest request) {
    Map<String, Object> params = new HashMap<>();
    params.put("rootKey", request.rootProcessDefinitionKey());
    params.put("defKey", request.processDefinitionKey());
    params.put("activityId", request.activityId());

    StringBuilder select = new StringBuilder("select distinct i.CONFIGURATION_\n")
        .append(treeJoins());
    if (request.calledFrom() != null) {
      // Inner joins here: a root-level incident has no caller and cannot match.
      select.append(callerJoins("join"));
    }
    select.append("""
        where  rd.KEY_ = :rootKey
        and    i.ID_   = i.ROOT_CAUSE_INCIDENT_ID_
        and    pd.KEY_ = :defKey
        and    i.ACTIVITY_ID_ = :activityId
        and    i.CONFIGURATION_ is not null
        """);
    appendOptionalIncidentFilters(select, params, request.incidentType(), request.tenantId());
    if (request.calledFrom() != null) {
      // The caller is part of the group key: the same child BPMN called from two call
      // activities under one root is two report groups, and a retry posted from one
      // must not cross into the other.
      select.append("and    sd.KEY_ = :parentKey\n");
      select.append("and    sup.ACT_ID_ = :callActivityId\n");
      params.put("parentKey", request.calledFrom().processDefinitionKey());
      params.put("callActivityId", request.calledFrom().callActivityId());
    }
    if (request.processDefinitionVersion() != null) {
      select.append("and    pd.VERSION_ = :defVersion\n");
      params.put("defVersion", request.processDefinitionVersion());
    }
    // Same half-open window as the report, so a retry posted from a time-filtered
    // view touches exactly the slice that view displayed.
    if (request.incidentTimestampAfterDate() != null) {
      select.append("and    i.INCIDENT_TIMESTAMP_ >= :timestampAfter\n");
      params.put("timestampAfter", request.incidentTimestampAfterDate());
    }
    if (request.incidentTimestampBeforeDate() != null) {
      select.append("and    i.INCIDENT_TIMESTAMP_ < :timestampBefore\n");
      params.put("timestampBefore", request.incidentTimestampBeforeDate());
    }

    return jdbc.queryForList(select.toString(), params, String.class);
  }

  private String treeJoins() {
    return "from   " + sql.table(INCIDENT) + "  i\n"
        + "join   " + sql.table(EXECUTION) + " pi   on pi.ID_   = i.PROC_INST_ID_\n"
        + "join   " + sql.table(EXECUTION) + " root on root.ID_ = pi.ROOT_PROC_INST_ID_\n"
        + "join   " + sql.table(PROCESS_DEFINITION) + "   rd   on rd.ID_   = root.PROC_DEF_ID_\n"
        + "join   " + sql.table(PROCESS_DEFINITION) + "   pd   on pd.ID_   = i.PROC_DEF_ID_\n";
  }

  /**
   * The caller of the failing instance: its call-activity execution in the parent and
   * that parent's definition. Outer for the report (root-level incidents have none),
   * inner for a caller-scoped retry.
   */
  private String callerJoins(String joinKind) {
    return joinKind + " " + sql.table(EXECUTION) + " sup on sup.ID_ = pi.SUPER_EXEC_\n"
        + joinKind + " " + sql.table(PROCESS_DEFINITION) + " sd  on sd.ID_  = sup.PROC_DEF_ID_\n";
  }

  private static void appendOptionalIncidentFilters(
      StringBuilder select, Map<String, Object> params, String incidentType, String tenantId) {
    if (incidentType != null) {
      select.append("and    i.INCIDENT_TYPE_ = :incidentType\n");
      params.put("incidentType", incidentType);
    }
    if (tenantId != null) {
      select.append("and    i.TENANT_ID_ = :tenantId\n");
      params.put("tenantId", tenantId);
    }
  }

  private static IncidentGroupRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new IncidentGroupRow(
        rs.getString("ROOT_KEY_"),
        rs.getString("DEF_ID_"),
        rs.getString("DEF_KEY_"),
        rs.getString("DEF_NAME_"),
        rs.getInt("DEF_VERSION_"),
        rs.getString("ACTIVITY_ID_"),
        rs.getString("INCIDENT_TYPE_"),
        rs.getString("TENANT_ID_"),
        rs.getString("PARENT_KEY_"),
        rs.getString("CALL_ACTIVITY_ID_"),
        rs.getLong("INCIDENTS_"),
        rs.getLong("INSTANCES_"),
        toDate(rs.getTimestamp("OLDEST_")),
        toDate(rs.getTimestamp("NEWEST_")),
        rs.getString("SAMPLE_MSG_"));
  }

  private static Date toDate(Timestamp timestamp) {
    // Date, not Instant, on purpose: the engine REST application's Jackson setup
    // formats java.util.Date with the engine's own date format, and knows nothing
    // about java.time.
    return timestamp == null ? null : new Date(timestamp.getTime());
  }
}
