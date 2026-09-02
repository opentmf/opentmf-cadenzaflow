package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The statements are asserted textually (prefixing, predicate presence, bound
 * parameters); whether they run and aggregate correctly against a real engine schema is
 * IncidentOperationsIT's job.
 *
 * @author Cezmi Aslan
 */
class IncidentGroupRepositoryTests {

  private NamedParameterJdbcTemplate jdbc;
  private IncidentGroupRepository repository;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    var sqlSupport = mock(EngineSqlSupport.class);
    when(sqlSupport.table(anyString()))
        .thenAnswer(invocation -> "cadenzaflow." + invocation.getArgument(0));
    repository = new IncidentGroupRepository(jdbc, sqlSupport);
  }

  @SuppressWarnings("unchecked")
  private String capturedGroupSql(Map<String, Object> expectedParams) {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
    assertThat(params.getValue()).containsAllEntriesOf(expectedParams);
    return sql.getValue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void groupsReturnsExactlyWhatTheSelectMapped() {
    List<IncidentGroupRow> mapped = List.of(mock(IncidentGroupRow.class));
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(mapped);
    assertThat(repository.groups("orderFulfilment", null, null, null, null, null))
        .isSameAs(mapped);
  }

  @Test
  void groupSelectJoinsTheCallTreeOnPrefixedTablesAndFiltersOriginatingIncidents() {
    repository.groups("orderFulfilment", null, null, null, null, null);

    String sql = capturedGroupSql(Map.of("rootKey", "orderFulfilment"));
    assertThat(sql)
        .contains("cadenzaflow.ACT_RU_INCIDENT")
        .contains("cadenzaflow.ACT_RU_EXECUTION")
        .contains("cadenzaflow.ACT_RE_PROCDEF")
        .contains("root.ID_ = pi.ROOT_PROC_INST_ID_")
        .contains("sup.ID_ = pi.SUPER_EXEC_")
        .contains("i.ID_   = i.ROOT_CAUSE_INCIDENT_ID_")
        .contains("group by")
        .contains("order by INCIDENTS_ desc")
        .doesNotContain("INCIDENT_TYPE_ = :incidentType")
        .doesNotContain("TENANT_ID_ = :tenantId")
        .doesNotContain(":timestampAfter")
        .doesNotContain(":timestampBefore")
        .doesNotContain("having");
  }

  @Test
  void groupSelectScopesTheTimeRangeHalfOpenOnTheOriginatingTimestamp() {
    Date after = Date.from(Instant.parse("2026-09-01T14:00:00Z"));
    Date before = Date.from(Instant.parse("2026-09-02T14:00:00Z"));
    repository.groups("orderFulfilment", null, null, after, before, null);

    String sql = capturedGroupSql(Map.of("timestampAfter", after, "timestampBefore", before));
    assertThat(sql)
        .as("after inclusive, before exclusive - consecutive windows chain gaplessly")
        .contains("i.INCIDENT_TIMESTAMP_ >= :timestampAfter")
        .contains("i.INCIDENT_TIMESTAMP_ < :timestampBefore");
  }

  @Test
  void groupSelectAppendsEachOptionalFilterOnlyWhenGiven() {
    repository.groups("orderFulfilment", "failedExternalTask", "tenant-1", null, null, 4);

    String sql = capturedGroupSql(Map.of(
        "rootKey", "orderFulfilment",
        "incidentType", "failedExternalTask",
        "tenantId", "tenant-1",
        "minIncidents", 4));
    assertThat(sql)
        .contains("i.INCIDENT_TYPE_ = :incidentType")
        .contains("i.TENANT_ID_ = :tenantId")
        .contains("having count(*) >= :minIncidents");
  }

  @Test
  @SuppressWarnings("unchecked")
  void groupRowsCarryEveryColumnOfTheSelect() throws Exception {
    repository.groups("orderFulfilment", null, null, null, null, null);
    ArgumentCaptor<RowMapper<IncidentGroupRow>> mapper =
        ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc).query(anyString(), anyMap(), mapper.capture());

    Date oldest = Date.from(Instant.parse("2026-08-31T22:10:04Z"));
    Date newest = Date.from(Instant.parse("2026-09-01T07:58:41Z"));
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("ROOT_KEY_")).thenReturn("orderFulfilment");
    when(rs.getString("DEF_ID_")).thenReturn("reserveStock:8:id");
    when(rs.getString("DEF_KEY_")).thenReturn("reserveStock");
    when(rs.getString("DEF_NAME_")).thenReturn("Reserve stock");
    when(rs.getInt("DEF_VERSION_")).thenReturn(8);
    when(rs.getString("ACTIVITY_ID_")).thenReturn("callWms");
    when(rs.getString("INCIDENT_TYPE_")).thenReturn("failedExternalTask");
    when(rs.getString("TENANT_ID_")).thenReturn(null);
    when(rs.getString("PARENT_KEY_")).thenReturn("orderFulfilment");
    when(rs.getString("CALL_ACTIVITY_ID_")).thenReturn("reserve");
    when(rs.getLong("INCIDENTS_")).thenReturn(1842L);
    when(rs.getLong("INSTANCES_")).thenReturn(1842L);
    when(rs.getTimestamp("OLDEST_")).thenReturn(new Timestamp(oldest.getTime()));
    when(rs.getTimestamp("NEWEST_")).thenReturn(new Timestamp(newest.getTime()));
    when(rs.getString("SAMPLE_MSG_")).thenReturn("WMS returned 503");

    assertThat(mapper.getValue().mapRow(rs, 0)).isEqualTo(new IncidentGroupRow(
        "orderFulfilment", "reserveStock:8:id", "reserveStock", "Reserve stock", 8,
        "callWms", "failedExternalTask", null, "orderFulfilment", "reserve",
        1842, 1842, oldest, newest, "WMS returned 503"));

    // Null timestamps (defensive SQL-null handling) map to null, not an NPE.
    when(rs.getTimestamp("OLDEST_")).thenReturn(null);
    when(rs.getTimestamp("NEWEST_")).thenReturn(null);
    IncidentGroupRow nullDates = mapper.getValue().mapRow(rs, 1);
    assertThat(nullDates.oldestIncident()).isNull();
    assertThat(nullDates.newestIncident()).isNull();
  }

  @SuppressWarnings("unchecked")
  private String capturedRetrySql(Map<String, Object> expectedParams) {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).queryForList(sql.capture(), params.capture(), eq(String.class));
    assertThat(params.getValue()).containsAllEntriesOf(expectedParams);
    return sql.getValue();
  }

  @Test
  void retrySelectNarrowsTheSameTreeToTheSelectorAndOnlyRetryableIncidents() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("job-1"));

    List<String> ids = repository.retryConfigurations(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedExternalTask",
        null, null, null, null, 1));

    assertThat(ids).containsExactly("job-1");
    String sql = capturedRetrySql(Map.of(
        "rootKey", "orderFulfilment",
        "defKey", "reserveStock",
        "activityId", "callWms",
        "incidentType", "failedExternalTask"));
    assertThat(sql)
        .contains("select distinct i.CONFIGURATION_")
        .contains("i.ID_   = i.ROOT_CAUSE_INCIDENT_ID_")
        .contains("i.CONFIGURATION_ is not null")
        .doesNotContain("group by")
        .doesNotContain("TENANT_ID_ = :tenantId")
        .doesNotContain("VERSION_ = :defVersion")
        .doesNotContain(":timestampAfter")
        .doesNotContain(":timestampBefore");
  }

  @Test
  void retrySelectHonoursTheSelectorsHalfOpenWindow() {
    repository.retryConfigurations(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedExternalTask",
        null, null, "2026-09-01T14:00:00Z", "2026-09-02T14:00:00Z", 1));

    String sql = capturedRetrySql(Map.of(
        "timestampAfter", Date.from(Instant.parse("2026-09-01T14:00:00Z")),
        "timestampBefore", Date.from(Instant.parse("2026-09-02T14:00:00Z"))));
    assertThat(sql)
        .as("a retry posted from a time-filtered view must stay inside that window")
        .contains("i.INCIDENT_TIMESTAMP_ >= :timestampAfter")
        .contains("i.INCIDENT_TIMESTAMP_ < :timestampBefore");
  }

  @Test
  void retrySelectScopesToTenantAndVersionWhenTheSelectorNamesThem() {
    repository.retryConfigurations(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedExternalTask",
        "tenant-1", 8, null, null, 1));

    String sql = capturedRetrySql(Map.of("tenantId", "tenant-1", "defVersion", 8));
    assertThat(sql)
        .contains("i.TENANT_ID_ = :tenantId")
        .contains("pd.VERSION_ = :defVersion");
  }
}
