package org.opentmf.cadenzaflow.extensions.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the incident operations endpoints mounted in the engine REST
 * application under {@code /engine-rest/extensions/incident}.
 *
 * @author Cezmi Aslan
 */
@Component
@ConfigurationProperties("cadenzaflow.incidents")
@Validated
public class IncidentOperationsProperties {

  @Valid
  private final Retry retry = new Retry();

  public Retry getRetry() {
    return retry;
  }

  public static class Retry {

    /**
     * Ids handed to one engine batch. One batch per chunk, so a failing chunk does not
     * stall the others.
     */
    @Min(1)
    private int chunkSize = 20000;

    public int getChunkSize() {
      return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
      this.chunkSize = chunkSize;
    }
  }
}
