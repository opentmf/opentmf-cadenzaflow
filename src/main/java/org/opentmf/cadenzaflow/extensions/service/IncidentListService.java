package org.opentmf.cadenzaflow.extensions.service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.cadenzaflow.bpm.engine.runtime.IncidentQuery;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.opentmf.cadenzaflow.extensions.repository.ActivityNameLookup;
import org.opentmf.cadenzaflow.extensions.repository.ProcessDefinitionLookup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The incident list reads through the engine's stock {@code IncidentQuery} — a count
 * plus one offset page on the existing indexes — and enriches only the page's rows
 * (at most {@code max-limit} of them) with definition and activity names. Deliberately
 * NOT the tree-scoped SQL of the grouped report: every filter here exists on the stock
 * query, and group drill-down works by filtering {@code processDefinitionKeyIn} +
 * {@code activityId} + {@code incidentType} — the propagated copies then fall out
 * naturally, because a copy sits in a DIFFERENT definition, on the call activity.
 *
 * @author Cezmi Aslan
 */
@Service
public class IncidentListService {

  private final RuntimeService runtimeService;
  private final ProcessDefinitionLookup definitionLookup;
  private final ActivityNameLookup activityNameLookup;

  public IncidentListService(
      RuntimeService runtimeService,
      ProcessDefinitionLookup definitionLookup,
      ActivityNameLookup activityNameLookup) {
    this.runtimeService = runtimeService;
    this.definitionLookup = definitionLookup;
    this.activityNameLookup = activityNameLookup;
  }

  /**
   * @param hrefBasePath the request's base path up to and including {@code /engine-rest/},
   *     from which each row's {@code href} to the stock single-get is built
   */
  public Page<IncidentRow> page(
      IncidentListFilter filter, Pageable pageable, Sort sort, String hrefBasePath) {
    IncidentQuery query = buildQuery(filter);
    IncidentSortMapping.apply(query, sort);

    long total = query.count();
    List<Incident> incidents = query.listPage(
        (int) Math.min(pageable.getOffset(), Integer.MAX_VALUE), pageable.getPageSize());
    // Per-request memo: every definition lookup is an engine COMMAND (its own
    // transaction and pool checkout, even on a deployment-cache hit), so resolve
    // each DISTINCT definition once per page, not once per row.
    Map<String, Optional<ProcessDefinition>> definitions = new HashMap<>();
    List<IncidentRow> rows = incidents.stream()
        .map(incident -> toRow(incident, hrefBasePath, definitions))
        .toList();
    return new PageImpl<>(rows, pageable, total);
  }

  private IncidentQuery buildQuery(IncidentListFilter filter) {
    IncidentQuery query = runtimeService.createIncidentQuery();
    if (filter.incidentType() != null) {
      query.incidentType(filter.incidentType());
    }
    if (has(filter.processDefinitionKeyIn())) {
      query.processDefinitionKeyIn(filter.processDefinitionKeyIn().toArray(String[]::new));
    }
    if (filter.processDefinitionId() != null) {
      query.processDefinitionId(filter.processDefinitionId());
    }
    if (filter.processInstanceId() != null) {
      query.processInstanceId(filter.processInstanceId());
    }
    if (filter.executionId() != null) {
      query.executionId(filter.executionId());
    }
    if (filter.activityId() != null) {
      query.activityId(filter.activityId());
    }
    if (filter.failedActivityId() != null) {
      query.failedActivityId(filter.failedActivityId());
    }
    if (filter.incidentMessageLike() != null) {
      query.incidentMessageLike(filter.incidentMessageLike());
    }
    if (filter.incidentTimestampAfter() != null) {
      // The contract is half-open: after is INCLUSIVE. The stock query only offers
      // strict '>', so shift by one millisecond - exact, because the engine writes
      // millisecond-precision timestamps (java.util.Date).
      query.incidentTimestampAfter(new Date(filter.incidentTimestampAfter().getTime() - 1));
    }
    if (filter.incidentTimestampBefore() != null) {
      query.incidentTimestampBefore(filter.incidentTimestampBefore());
    }
    if (has(filter.tenantIdIn())) {
      query.tenantIdIn(filter.tenantIdIn().toArray(String[]::new));
    }
    if (has(filter.jobDefinitionIdIn())) {
      query.jobDefinitionIdIn(filter.jobDefinitionIdIn().toArray(String[]::new));
    }
    if (filter.rootCauseIncidentId() != null) {
      query.rootCauseIncidentId(filter.rootCauseIncidentId());
    }
    return query;
  }

  /** Comma-list parameters arrive empty when absent; empty means "no filter". */
  private static boolean has(List<String> values) {
    return values != null && !values.isEmpty();
  }

  private IncidentRow toRow(Incident incident, String hrefBasePath,
      Map<String, Optional<ProcessDefinition>> definitions) {
    var definition = definitions.computeIfAbsent(
        incident.getProcessDefinitionId(), definitionLookup::byId);
    String activityName = incident.getActivityId() == null
        ? null
        : activityNameLookup
            .activity(incident.getProcessDefinitionId(), incident.getActivityId())
            .map(ActivityNameLookup.ActivityInfo::name)
            .orElse(null);
    return new IncidentRow(
        incident.getId(),
        hrefBasePath + "incident/" + incident.getId(),
        incident.getIncidentType(),
        incident.getIncidentTimestamp(),
        incident.getIncidentMessage(),
        incident.getActivityId(),
        activityName,
        incident.getFailedActivityId(),
        incident.getProcessDefinitionId(),
        definition.map(d -> d.getKey()).orElse(null),
        definition.map(d -> d.getName()).orElse(null),
        definition.map(d -> d.getVersion()).orElse(null),
        incident.getProcessInstanceId(),
        incident.getExecutionId(),
        incident.getCauseIncidentId(),
        incident.getRootCauseIncidentId(),
        incident.getId().equals(incident.getRootCauseIncidentId()),
        incident.getConfiguration(),
        incident.getJobDefinitionId(),
        incident.getTenantId(),
        incident.getAnnotation());
  }
}
