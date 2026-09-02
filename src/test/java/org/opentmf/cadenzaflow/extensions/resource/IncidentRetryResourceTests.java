package org.opentmf.cadenzaflow.extensions.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryResult;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupRetryService;

/**
 * Direct calls with a mocked service and a real Bean Validator: the resource runs
 * inside the engine's Jersey application, which has no validation feature, so the
 * explicit validation call IS the request validation and is pinned here.
 *
 * @author Cezmi Aslan
 */
class IncidentRetryResourceTests {

  private static ValidatorFactory validatorFactory;

  private IncidentGroupRetryService retryService;
  private IncidentRetryResource resource;

  @BeforeAll
  static void openValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @BeforeEach
  void setUp() {
    retryService = mock(IncidentGroupRetryService.class);
    resource = new IncidentRetryResource(retryService, validatorFactory.getValidator());
  }

  @Test
  void retryDelegatesAValidRequestToTheService() {
    IncidentGroupRetryRequest request = new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null,
        null, null, null, null, 1);
    IncidentGroupRetryResult result = new IncidentGroupRetryResult(0, List.of());
    when(retryService.retry(request)).thenReturn(result);

    assertThat(resource.retry(request)).isSameAs(result);
    verify(retryService).retry(request);
  }

  @Test
  void retryRefusesAMissingBody() {
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.retry(null))
        .withMessageContaining("body");
    verifyNoInteractions(retryService);
  }

  @Test
  void retryRefusesAnInvalidRequestNamingEveryViolatedField() {
    IncidentGroupRetryRequest request = new IncidentGroupRetryRequest(
        " ", "reserveStock", "callWms", "failedConnector", null,
        null, null, null, null, 0);

    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.retry(request))
        .withMessageContaining("rootProcessDefinitionKey")
        .withMessageContaining("incidentType")
        .withMessageContaining("retries");
    verifyNoInteractions(retryService);
  }

  @Test
  void retryRefusesAnUnparseableWindowBeforeTouchingTheEngine() {
    IncidentGroupRetryRequest request = new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null,
        null, null, "yesterday", null, 1);
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.retry(request))
        .withMessageContaining("incidentTimestampAfter");
    verifyNoInteractions(retryService);
  }
}
