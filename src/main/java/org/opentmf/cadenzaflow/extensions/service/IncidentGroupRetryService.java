package org.opentmf.cadenzaflow.extensions.service;

import java.util.ArrayList;
import java.util.List;
import org.cadenzaflow.bpm.engine.ExternalTaskService;
import org.cadenzaflow.bpm.engine.ManagementService;
import org.cadenzaflow.bpm.engine.batch.Batch;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.opentmf.cadenzaflow.extensions.config.IncidentOperationsProperties;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryResult;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupSelector;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Retries every incident of one report group as engine batches. The selector is
 * re-resolved in the database at retry time (ids are never round-tripped through the
 * client), the resulting job / external-task ids are chunked, and each chunk becomes
 * one engine batch so a failing chunk does not stall the others. Setting retries above
 * zero is also what resolves the incident and its ancestor copies inside the engine —
 * no separate resolve step exists or is needed.
 *
 * <p>No engine user id is set for the batch: engine authorization is skipped for
 * commands without an authentication, and setting one would demand engine-level batch
 * and job grants for a caller the edge RBAC has already admitted. The INFO line below,
 * carrying the edge principal, is the audit trail instead.</p>
 *
 * @author Cezmi Aslan
 */
@Service
public class IncidentGroupRetryService {

  private static final Logger log = LoggerFactory.getLogger(IncidentGroupRetryService.class);

  private final IncidentGroupRepository repository;
  private final ManagementService managementService;
  private final ExternalTaskService externalTaskService;
  private final IncidentOperationsProperties properties;

  public IncidentGroupRetryService(
      IncidentGroupRepository repository,
      ManagementService managementService,
      ExternalTaskService externalTaskService,
      IncidentOperationsProperties properties) {
    this.repository = repository;
    this.managementService = managementService;
    this.externalTaskService = externalTaskService;
    this.properties = properties;
  }

  public IncidentGroupRetryResult retry(IncidentGroupRetryRequest request) {
    List<String> configurationIds = repository.retryConfigurations(request);
    List<IncidentGroupRetryResult.BatchDescriptor> batches = new ArrayList<>();
    int chunkSize = properties.getRetry().getChunkSize();
    for (int from = 0; from < configurationIds.size(); from += chunkSize) {
      List<String> chunk =
          configurationIds.subList(from, Math.min(from + chunkSize, configurationIds.size()));
      Batch batch = createBatch(request, chunk);
      batches.add(new IncidentGroupRetryResult.BatchDescriptor(
          batch.getId(), batch.getType(), chunk.size()));
    }
    if (log.isInfoEnabled()) {
      log.info("incident group retry by '{}': {} -> {} incidents, batches {}",
          principal(), selectorOf(request), configurationIds.size(),
          batches.stream().map(IncidentGroupRetryResult.BatchDescriptor::id).toList());
    }
    return new IncidentGroupRetryResult(configurationIds.size(), batches);
  }

  private Batch createBatch(IncidentGroupRetryRequest request, List<String> chunk) {
    return switch (request.incidentType()) {
      case Incident.FAILED_JOB_HANDLER_TYPE ->
          managementService.setJobRetriesAsync(chunk, request.retries());
      case Incident.EXTERNAL_TASK_HANDLER_TYPE ->
          externalTaskService.setRetriesAsync(chunk, null, request.retries());
      default -> throw new IllegalArgumentException(
          "Unsupported incident type '" + request.incidentType()
              + "' - only failedJob and failedExternalTask incidents are retryable");
    };
  }

  private static IncidentGroupSelector selectorOf(IncidentGroupRetryRequest request) {
    return new IncidentGroupSelector(
        request.rootProcessDefinitionKey(), request.processDefinitionKey(),
        request.activityId(), request.incidentType(), request.tenantId(),
        request.incidentTimestampAfter(), request.incidentTimestampBefore());
  }

  private static String principal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "unknown" : authentication.getName();
  }
}
