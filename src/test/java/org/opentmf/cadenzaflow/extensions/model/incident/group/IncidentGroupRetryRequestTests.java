package org.opentmf.cadenzaflow.extensions.model.incident.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @author Cezmi Aslan
 */
class IncidentGroupRetryRequestTests {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void openValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  private static Set<ConstraintViolation<IncidentGroupRetryRequest>> violationsOf(
      IncidentGroupRetryRequest request) {
    return validator.validate(request);
  }

  @Test
  void acceptAFullSelectorWithPositiveRetries() {
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedExternalTask",
        "tenant-1", 8, "2026-09-01T14:00:00Z", "2026-09-02T14:00:00Z", 1))).isEmpty();
  }

  @Test
  void acceptNullTenantAndVersionAsUnscoped() {
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        null, null, 3))).isEmpty();
  }

  @Test
  void refuseBlankKeyFields() {
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        " ", "", null, "failedJob", null, null, null, null, 1))).hasSize(3);
  }

  @Test
  void refuseAnUnknownIncidentType() {
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedConnector", null, null,
        null, null, 1))).hasSize(1);
  }

  @Test
  void refuseZeroAndMissingRetries() {
    // retries 0 would resolve nothing and still create a batch; the engine treats it
    // as "no retries left", so it is rejected before it reaches the engine.
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        null, null, 0))).hasSize(1);
    assertThat(violationsOf(new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        null, null, null))).hasSize(1);
  }

  @Test
  void windowAccessorsParseBothFormatsAndRefuseGarbage() {
    IncidentGroupRetryRequest windowed = new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        "2026-09-01T14:00:00Z", "2026-09-02T14:00:00.000+0000", 1);
    assertThat(windowed.incidentTimestampAfterDate())
        .isEqualTo(Date.from(Instant.parse("2026-09-01T14:00:00Z")));
    assertThat(windowed.incidentTimestampBeforeDate())
        .isEqualTo(Date.from(Instant.parse("2026-09-02T14:00:00Z")));

    IncidentGroupRetryRequest unbounded = new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        null, null, 1);
    assertThat(unbounded.incidentTimestampAfterDate()).isNull();
    assertThat(unbounded.incidentTimestampBeforeDate()).isNull();

    IncidentGroupRetryRequest garbage = new IncidentGroupRetryRequest(
        "orderFulfilment", "reserveStock", "callWms", "failedJob", null, null,
        "yesterday", null, 1);
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(garbage::incidentTimestampAfterDate)
        .withMessageContaining("incidentTimestampAfter");
  }
}
