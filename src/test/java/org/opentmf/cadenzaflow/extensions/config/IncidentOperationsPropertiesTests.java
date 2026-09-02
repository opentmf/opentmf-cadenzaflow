package org.opentmf.cadenzaflow.extensions.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

/**
 * @author Cezmi Aslan
 */
class IncidentOperationsPropertiesTests {

  @Test
  void defaultChunkSizeIsTwentyThousand() {
    assertThat(new IncidentOperationsProperties().getRetry().getChunkSize()).isEqualTo(20000);
  }

  @Test
  void chunkSizeIsSettableAndCascadeValidated() {
    IncidentOperationsProperties properties = new IncidentOperationsProperties();
    properties.getRetry().setChunkSize(500);
    assertThat(properties.getRetry().getChunkSize()).isEqualTo(500);

    properties.getRetry().setChunkSize(0);
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      // @Valid on the retry field is what makes the nested @Min reachable at all.
      assertThat(factory.getValidator().validate(properties)).hasSize(1);
    }
  }
}
