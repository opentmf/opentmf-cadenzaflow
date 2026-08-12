package org.opentmf.cadenzaflow.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Proves that the masking rules shipped in {@code logback-masking.xml} actually redact.
 *
 * <p>The file is a Logback {@code <included>} fragment, so it cannot be configured on its own;
 * the test wraps it in a minimal configuration exactly the way {@code logback-spring.xml} and an
 * operator-mounted logback file do, then encodes synthetic events through the resulting appender.
 * Asserting on the encoder output rather than on captured stdout keeps the test independent of
 * where the appender happens to write.
 *
 * @author cezmi-aslan
 */
class LogbackMaskingTests {

  private static final String MASK = "****";

  private static final String CONFIGURATION =
      """
      <configuration>
        <include resource="logback-masking.xml"/>
        <root level="INFO">
          <appender-ref ref="JSON"/>
        </root>
      </configuration>
      """;

  private LoggerContext context;
  private Encoder<ILoggingEvent> encoder;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void configureFromTheShippedFragment() throws Exception {
    context = new LoggerContext();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    configurator.doConfigure(
        new ByteArrayInputStream(CONFIGURATION.getBytes(StandardCharsets.UTF_8)));

    // A mistyped element leaves Joran WARNing and the encoder running UNMASKED, so
    // anything above INFO in the configuration status is a failure, not noise.
    assertThat(context.getStatusManager().getCopyOfStatusList())
        .as("logback-masking.xml must configure without warnings")
        .allMatch(status -> status.getLevel() == Status.INFO, "level == INFO");

    OutputStreamAppender<ILoggingEvent> appender =
        (OutputStreamAppender<ILoggingEvent>)
            context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("JSON");
    assertThat(appender).as("the fragment must define an appender named JSON").isNotNull();
    encoder = appender.getEncoder();
  }

  @AfterEach
  void stopContext() {
    context.stop();
  }

  private String encode(String message, Map<String, String> mdc, Object... arguments) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(context);
    event.setLoggerName("org.opentmf.cadenzaflow.Test");
    event.setLevel(Level.INFO);
    event.setMessage(message);
    event.setArgumentArray(arguments);
    event.setMDCPropertyMap(mdc);
    event.setTimeStamp(System.currentTimeMillis());
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }

  @Nested
  @DisplayName("path masks (a field of that name, at any depth)")
  class PathMasks {

    @Test
    void maskCredentialFieldsFromTheMdc() {
      String json =
          encode(
              "authenticating",
              Map.of(
                  "password", "top1secret",
                  "client_secret", "SBl8Q~oNOTAREALSECRET",
                  "access_token", "eyJhbGciOiJSUzI1NiJ9.payload.signature"));

      assertThat(json)
          .doesNotContain("top1secret", "SBl8Q~oNOTAREALSECRET", "eyJhbGciOiJSUzI1NiJ9")
          .contains("\"password\":\"" + MASK + "\"")
          .contains("\"client_secret\":\"" + MASK + "\"")
          .contains("\"access_token\":\"" + MASK + "\"");
    }

    @Test
    void maskPersonalDataFields() {
      String json = encode("user resolved", Map.of("email", "gokhan@example.com"));

      assertThat(json)
          .doesNotContain("gokhan@example.com")
          .contains("\"email\":\"" + MASK + "\"");
    }

    @Test
    void leaveCorrelationIdsAlone() {
      String json =
          encode(
              "task completed",
              Map.of("processInstanceId", "8f1c0b2e-4a3d-11f0-9c1a-0242ac120002"));

      assertThat(json).contains("8f1c0b2e-4a3d-11f0-9c1a-0242ac120002");
    }
  }

  @Nested
  @DisplayName("value masks (regexes over every string value, message text included)")
  class ValueMasks {

    @Test
    void maskAnIbanInsideTheMessageText() {
      String json = encode("payment for DE89370400440532013000 accepted", Map.of());

      assertThat(json).doesNotContain("DE89370400440532013000").contains(MASK);
    }

    @Test
    void maskAGermanPhoneNumberInsideTheMessageText() {
      String json = encode("notifying +4915112345678 by sms", Map.of());

      assertThat(json).doesNotContain("+4915112345678");
    }

    @Test
    void maskLongDigitRunsButNotOrdinaryEngineNumbers() {
      String json = encode("card 4111111111111111 for job with 3 retries", Map.of());

      assertThat(json).doesNotContain("4111111111111111").contains("3 retries");
    }

    @Test
    void leaveEmailAddressesInMessageTextReadable() {
      // Deliberate: the CadenzaFlow user id IS the e-mail address, so a value-level
      // e-mail mask would erase the actor from every attribution line. Structured
      // occurrences are covered by the `email` path mask instead.
      String json = encode("user gokhan@example.com authorized", Map.of());

      assertThat(json).contains("gokhan@example.com");
    }
  }
}
