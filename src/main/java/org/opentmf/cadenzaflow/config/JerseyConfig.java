package org.opentmf.cadenzaflow.config;

import jakarta.ws.rs.ApplicationPath;
import java.util.logging.Level;
import org.cadenzaflow.bpm.spring.boot.starter.rest.CamundaJerseyResourceConfig;
import org.glassfish.jersey.logging.LoggingFeature;
import org.springframework.context.annotation.Configuration;

/**
 * Configures request/response logging for Camunda.
 * <p>To enable request / response logging, add this to your logging configuration:
 * <pre>
 * {@code
 * <logger name="org.glassfish.jersey.logging.LoggingFeature" level="DEBUG" />
 * }
 * </pre>
 *
 * @author Gokhan Demir
 */
@Configuration
@ApplicationPath("/engine-rest")
public class JerseyConfig extends CamundaJerseyResourceConfig {

  @Override
  protected void registerAdditionalResources() {
    super.registerAdditionalResources();

    register(LoggingFeature.class)
        .property(LoggingFeature.DEFAULT_LOGGER_LEVEL, Level.INFO.getName())
        .property(LoggingFeature.LOGGING_FEATURE_VERBOSITY_CLIENT,
            LoggingFeature.Verbosity.PAYLOAD_ANY)
        .property(LoggingFeature.LOGGING_FEATURE_VERBOSITY_SERVER,
            LoggingFeature.Verbosity.PAYLOAD_ANY)
        .property(LoggingFeature.LOGGING_FEATURE_MAX_ENTITY_SIZE, 8192);
  }
}
