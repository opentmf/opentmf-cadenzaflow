package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The one-row-per-chain statements, asserted textually (prefixing, the originating predicate,
 * each filter's column and bound parameter, the order); whether they select the right rows
 * from a real engine schema is IncidentOperationsIT's job.
 *
 * @author Cezmi Aslan
 */
class IncidentChainQueryTests {

  private static final IncidentListFilter NO_FILTER = new IncidentListFilter(
      null, null, null, null, null, null, null, null, null, null, null, null, null, true);

  private NamedParameterJdbcTemplate jdbc;
  private IncidentChainQuery query;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    var sqlSupport = mock(EngineSqlSupport.class);
    when(sqlSupport.table(anyString()))
        .thenAnswer(invocation -> "cadenzaflow." + invocation.getArgument(0));
    query = new IncidentChainQuery(jdbc, sqlSupport);
  }

  @Test
  void keepOnlyTheOriginatingIncidentOfEachChain() {
    IncidentChainQuery.Where where = query.where(NO_FILTER);

    assertThat(where.sql()).isEqualTo("where  RES.ID_ = RES.ROOT_CAUSE_INCIDENT_ID_\n");
    assertThat(where.params()).isEmpty();
  }

  @Test
  void expressEveryListFilterOnItsIncidentColumn() {
    Date after = new Date(1_000);
    Date before = new Date(2_000);
    IncidentChainQuery.Where where = query.where(new IncidentListFilter(
        "failedJob", List.of("a", "b"), "def-1", "pi-1", "ex-1", "act-1", "failed-1", "%503%",
        after, before, List.of("t1"), List.of("jd1"), "root-1", true));

    assertThat(where.sql())
        .contains("and    exists (select 1 from cadenzaflow.ACT_RE_PROCDEF PD"
            + " where PD.ID_ = RES.PROC_DEF_ID_ and PD.KEY_ in (:definitionKeys))")
        .contains("RES.INCIDENT_TYPE_ = :incidentType")
        .contains("RES.PROC_DEF_ID_ = :processDefinitionId")
        .contains("RES.PROC_INST_ID_ = :processInstanceId")
        .contains("RES.EXECUTION_ID_ = :executionId")
        .contains("RES.ACTIVITY_ID_ = :activityId")
        .contains("RES.FAILED_ACTIVITY_ID_ = :failedActivityId")
        .contains("RES.INCIDENT_MSG_ like :incidentMessageLike")
        .as("the list's window is half-open: after inclusive, before exclusive")
        .contains("RES.INCIDENT_TIMESTAMP_ >= :incidentTimestampAfter")
        .contains("RES.INCIDENT_TIMESTAMP_ < :incidentTimestampBefore")
        .contains("RES.TENANT_ID_ in (:tenantIds)")
        .contains("RES.JOB_DEF_ID_ in (:jobDefinitionIds)")
        .contains("RES.ROOT_CAUSE_INCIDENT_ID_ = :rootCauseIncidentId");
    assertThat(where.params()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
        Map.entry("incidentType", "failedJob"),
        Map.entry("definitionKeys", List.of("a", "b")),
        Map.entry("processDefinitionId", "def-1"),
        Map.entry("processInstanceId", "pi-1"),
        Map.entry("executionId", "ex-1"),
        Map.entry("activityId", "act-1"),
        Map.entry("failedActivityId", "failed-1"),
        Map.entry("incidentMessageLike", "%503%"),
        Map.entry("incidentTimestampAfter", after),
        Map.entry("incidentTimestampBefore", before),
        Map.entry("tenantIds", List.of("t1")),
        Map.entry("jobDefinitionIds", List.of("jd1")),
        Map.entry("rootCauseIncidentId", "root-1")));
  }

  @Test
  void orderByTheListsSortWithTheIdAsTieBreaker() {
    assertThat(IncidentChainQuery.orderBy(Sort.by(Sort.Order.desc("incidentTimestamp"),
        Sort.Order.asc("activityId"))))
        .isEqualTo("order by RES.INCIDENT_TIMESTAMP_ desc, RES.ACTIVITY_ID_ asc, RES.ID_ asc");
    assertThat(IncidentChainQuery.orderBy(Sort.by(Sort.Order.desc("id"))))
        .as("an explicit id order is the tie-breaker already").isEqualTo("order by RES.ID_ desc");
    assertThat(IncidentChainQuery.orderBy(Sort.unsorted())).isEqualTo("order by RES.ID_ asc");
  }

  @Test
  void refuseASortFieldWithoutAColumn() {
    Sort unknown = Sort.by("annotation");

    assertThatIllegalArgumentException().isThrownBy(() -> IncidentChainQuery.orderBy(unknown))
        .withMessageContaining("annotation");
  }

  @Test
  @SuppressWarnings("unchecked")
  void pageAndCountReadTheSameChains() {
    when(jdbc.queryForObject(anyString(), any(Map.class), eq(Long.class))).thenReturn(3L);

    assertThat(query.count(NO_FILTER)).isEqualTo(3);
    query.page(NO_FILTER, Sort.by(Sort.Order.desc("incidentTimestamp")), 40, 20);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
        .startsWith("select RES.* from cadenzaflow.ACT_RU_INCIDENT RES\n"
            + "where  RES.ID_ = RES.ROOT_CAUSE_INCIDENT_ID_\n")
        .endsWith("order by RES.INCIDENT_TIMESTAMP_ desc, RES.ID_ asc\n"
            + "offset :offset rows fetch next :limit rows only");
    assertThat(params.getValue()).containsEntry("offset", 40L).containsEntry("limit", 20);
    verify(jdbc).queryForObject(eq("select count(*) from cadenzaflow.ACT_RU_INCIDENT RES\n"
        + "where  RES.ID_ = RES.ROOT_CAUSE_INCIDENT_ID_\n"), any(Map.class), eq(Long.class));
  }
}
