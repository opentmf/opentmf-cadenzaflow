package org.opentmf.cadenzaflow.extensions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.IntStream;
import org.cadenzaflow.bpm.engine.ExternalTaskService;
import org.cadenzaflow.bpm.engine.ManagementService;
import org.cadenzaflow.bpm.engine.batch.Batch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.config.IncidentOperationsProperties;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryResult;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRepository;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * @author Cezmi Aslan
 */
class IncidentGroupRetryServiceTests {

  private static final int CHUNK_SIZE = 3;

  private IncidentGroupRepository repository;
  private ManagementService managementService;
  private ExternalTaskService externalTaskService;
  private IncidentGroupRetryService service;

  private ListAppender<ILoggingEvent> captured;
  private Logger logger;

  @BeforeEach
  void setUp() {
    repository = mock(IncidentGroupRepository.class);
    managementService = mock(ManagementService.class);
    externalTaskService = mock(ExternalTaskService.class);
    IncidentOperationsProperties properties = new IncidentOperationsProperties();
    properties.getRetry().setChunkSize(CHUNK_SIZE);
    service = new IncidentGroupRetryService(
        repository, managementService, externalTaskService, properties);

    logger = (Logger) LoggerFactory.getLogger(IncidentGroupRetryService.class);
    captured = new ListAppender<>();
    captured.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(captured);
    captured.stop();
    SecurityContextHolder.clearContext();
  }

  private static IncidentGroupRetryRequest request(String incidentType) {
    return new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", incidentType, null,
        null, null, null, null, 1);
  }

  /**
   * Built BEFORE the {@code when(...).thenReturn(...)} that hands it out: creating and
   * stubbing a mock inside a {@code thenReturn} argument nests one stubbing in another,
   * which Mockito rejects as UnfinishedStubbing.
   */
  private static Batch batch(String id, String type) {
    Batch batch = mock(Batch.class);
    when(batch.getId()).thenReturn(id);
    when(batch.getType()).thenReturn(type);
    return batch;
  }

  private void idsInRepository(int count) {
    when(repository.retryConfigurations(any())).thenReturn(
        IntStream.range(0, count).mapToObj(i -> "id-" + i).toList());
  }

  @Test
  void routeFailedExternalTaskIncidentsToTheExternalTaskService() {
    idsInRepository(2);
    Batch created = batch("b-1", "set-external-task-retries");
    when(externalTaskService.setRetriesAsync(anyList(), isNull(), anyInt()))
        .thenReturn(created);

    IncidentGroupRetryResult result = service.retry(request("failedExternalTask"));

    verify(externalTaskService).setRetriesAsync(List.of("id-0", "id-1"), null, 1);
    verifyNoInteractions(managementService);
    assertThat(result.incidentCount()).isEqualTo(2);
    assertThat(result.batches()).containsExactly(
        new IncidentGroupRetryResult.BatchDescriptor("b-1", "set-external-task-retries", 2));
  }

  @Test
  void routeFailedJobIncidentsToTheManagementService() {
    idsInRepository(1);
    Batch created = batch("b-1", "set-job-retries");
    when(managementService.setJobRetriesAsync(anyList(), anyInt())).thenReturn(created);

    IncidentGroupRetryResult result = service.retry(request("failedJob"));

    verify(managementService).setJobRetriesAsync(List.of("id-0"), 1);
    verifyNoInteractions(externalTaskService);
    assertThat(result.batches()).containsExactly(
        new IncidentGroupRetryResult.BatchDescriptor("b-1", "set-job-retries", 1));
  }

  @Test
  void answerZeroAndCreateNoBatchForAnEmptyGroup() {
    // Someone else retried first - an outcome, not an error.
    idsInRepository(0);

    IncidentGroupRetryResult result = service.retry(request("failedJob"));

    assertThat(result.incidentCount()).isZero();
    assertThat(result.batches()).isEmpty();
    verifyNoInteractions(managementService, externalTaskService);
  }

  @Test
  void splitTheIdsIntoOneBatchPerChunk() {
    // chunk + 1 ids: the boundary that catches both an off-by-one loss of the last id
    // and a spurious empty trailing chunk.
    idsInRepository(CHUNK_SIZE + 1);
    Batch first = batch("b-1", "set-job-retries");
    Batch second = batch("b-2", "set-job-retries");
    when(managementService.setJobRetriesAsync(anyList(), anyInt())).thenReturn(first, second);

    IncidentGroupRetryResult result = service.retry(request("failedJob"));

    verify(managementService).setJobRetriesAsync(List.of("id-0", "id-1", "id-2"), 1);
    verify(managementService).setJobRetriesAsync(List.of("id-3"), 1);
    assertThat(result.incidentCount()).isEqualTo(CHUNK_SIZE + 1);
    assertThat(result.batches()).extracting(IncidentGroupRetryResult.BatchDescriptor::size)
        .containsExactly(CHUNK_SIZE, 1);
  }

  @Test
  void keepAnExactlyFullChunkInOneBatch() {
    idsInRepository(CHUNK_SIZE);
    Batch created = batch("b-1", "set-job-retries");
    when(managementService.setJobRetriesAsync(anyList(), anyInt())).thenReturn(created);

    assertThat(service.retry(request("failedJob")).batches()).hasSize(1);
  }

  @Test
  void refuseAnIncidentTypeTheEngineCannotRetry() {
    // Bean Validation rejects this at the controller; the service still refuses rather
    // than silently doing nothing when called from elsewhere.
    idsInRepository(1);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.retry(request("failedConnector")))
        .withMessageContaining("failedConnector");
  }

  @Test
  void auditOneInfoLineNamingThePrincipalSelectorCountAndBatches() {
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("ops@example.com", "n/a"));
    idsInRepository(2);
    Batch created = batch("b-77", "set-external-task-retries");
    when(externalTaskService.setRetriesAsync(anyList(), isNull(), anyInt()))
        .thenReturn(created);

    service.retry(request("failedExternalTask"));

    assertThat(captured.list).hasSize(1);
    assertThat(captured.list.get(0).getFormattedMessage())
        .contains("ops@example.com")
        .contains("orderFulfilment")
        .contains("reserveStock")
        .contains("callWms")
        .contains("2 incidents")
        .contains("b-77");
  }

  @Test
  void auditAsUnknownWithoutAnAuthentication() {
    idsInRepository(0);

    service.retry(request("failedJob"));

    assertThat(captured.list).hasSize(1);
    assertThat(captured.list.get(0).getFormattedMessage()).contains("'unknown'");
  }
}
