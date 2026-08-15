package org.opentmf.cadenzaflow.config.logging;

import static net.logstash.logback.argument.StructuredArguments.keyValue;
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
    void maskStructuredArgumentFields() {
      // A path mask matches a field name at any depth, which includes the fields a
      // StructuredArguments call contributes - not just the MDC.
      String json =
          encode(
              "token issued {}",
              Map.of(),
              keyValue("accessToken", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.abc12345"));

      assertThat(json)
          .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
          .contains("\"accessToken\":\"" + MASK + "\"");
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
  @DisplayName("credential floor (shared verbatim with the platform fragment)")
  class CredentialFloor {

    @Test
    void maskKeyValueSecretsInsideTheMessageText() {
      String json = encode("retrying with password=hunter2 after 401", Map.of());

      assertThat(json).doesNotContain("hunter2").contains("password=" + MASK);
    }

    @Test
    void maskBearerTokensKeepingTheSchemeForTriage() {
      // The engine logs identity-plugin and REST-client failures verbatim, and a
      // Keycloak admin-client error is exactly where a bearer token reaches free
      // text - past every path mask, which can only match a field NAME.
      String json =
          encode(
              "keycloak admin call rejected: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.abc12345",
              Map.of());

      assertThat(json).doesNotContain("eyJhbGciOiJIUzI1NiJ9").contains("Bearer " + MASK);
    }

    @Test
    void maskBasicCredentialsKeepingTheScheme() {
      String json = encode("upstream sent Basic dXNlcjpwYXNzd29yZA==", Map.of());

      assertThat(json).doesNotContain("dXNlcjpwYXNzd29yZA").contains("Basic " + MASK);
    }

    @Test
    void maskBareJwtsWithNoAuthenticationScheme() {
      String json =
          encode("cached assertion eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhIn0.sig12345", Map.of());

      assertThat(json).doesNotContain("eyJhbGciOiJSUzI1NiJ9").contains(MASK);
    }

    @Test
    void maskCardNumbersWrittenInSeparatedGroups() {
      String json = encode("card 4111 1111 1111 1111 declined", Map.of());

      assertThat(json).doesNotContain("4111 1111 1111 1111").contains("card " + MASK);
    }
  }

  @Nested
  @DisplayName("value masks (regexes over every string value, message text included)")
  class ValueMasks {

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
  }

  /**
   * The three places this service's PII rules deliberately differ from the shared platform
   * fragment in {@code pia-team/dnms-service-template}. Each case exists so the difference is
   * provably a decision rather than drift: an edit that "aligns with the template" fails here and
   * has to argue with the reasoning recorded in {@code logback-masking.xml}, instead of quietly
   * changing what the engine writes to a log collector.
   */
  @Nested
  @DisplayName("deliberate divergences from the platform fragment")
  class DeliberateDivergences {

    @Test
    void maskTheWholeIbanRatherThanKeepingCountryAndCheckDigits() {
      // Stricter than the template, which keeps "DE89****" for payment triage.
      // This service never reconciles payments, so none of it is needed.
      String json = encode("payment for DE89370400440532013000 accepted", Map.of());

      assertThat(json).doesNotContain("DE89370400440532013000").doesNotContain("DE89").contains(MASK);
    }

    @Test
    void maskUnseparatedDigitRunsThatTheTemplateLeavesAlone() {
      // Stricter than the template, which leaves the unseparated form readable
      // because ids and epoch timestamps share its shape. Here the engine renders
      // process-variable dumps into message text, so an unseparated PAN really can
      // arrive in prose; the false positives are accepted.
      String json = encode("variable dump contains 1234567890123456", Map.of());

      assertThat(json).doesNotContain("1234567890123456").contains(MASK);
    }

    @Test
    void leaveEmailAddressesInMessageTextReadable() {
      // Looser than the template, which masks the local part and keeps the domain.
      // The CadenzaFlow user id IS the e-mail address, so a value-level e-mail mask
      // would erase the ACTOR from every authorization, task-claim and incident
      // line - destroying the audit trail it is meant to protect. Structured
      // occurrences are covered by the `email`/`mail` path masks instead.
      String json = encode("user gokhan@example.com authorized", Map.of());

      assertThat(json).contains("gokhan@example.com");
    }
  }
}
