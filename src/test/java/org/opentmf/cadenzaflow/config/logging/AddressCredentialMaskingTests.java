package org.opentmf.cadenzaflow.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A user name and password inside an address, logged through the image's own configuration:
 * {@code logback-spring.xml} with {@code LOGGING_APPENDER=JSON}, as both images set it, written by
 * a logger to the console appender. Made-up values only.
 *
 * @author cezmi-aslan
 */
class AddressCredentialMaskingTests {

  private static final String PASSWORD = "Cell-Pa55-not-real";

  private PrintStream originalOut;
  private ByteArrayOutputStream captured;
  private LoggerContext context;

  @BeforeEach
  void configureTheImagesLogging() throws Exception {
    originalOut = System.out;
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    context = new LoggerContext();
    context.setMDCAdapter(new LogbackMDCAdapter());
    context.putProperty("LOGGING_APPENDER", "JSON");
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    URL configuration = getClass().getClassLoader().getResource("logback-spring.xml");
    assertThat(configuration).as("the image's logback-spring.xml").isNotNull();
    configurator.doConfigure(configuration);
  }

  @AfterEach
  void restore() {
    context.stop();
    System.setOut(originalOut);
  }

  private String logged(String message) {
    context.getLogger("org.opentmf.cadenzaflow.Cell").info(message);
    assertThat(context.getStatusManager().getCopyOfStatusList())
        .as("the appender wrote the line without an error")
        .noneMatch(status -> status.getLevel() == Status.ERROR);
    return captured.toString(StandardCharsets.UTF_8);
  }

  private static String field(String json, String name) {
    Matcher matcher = Pattern.compile("\"" + name + "\":\"((?:[^\"\\\\]++|\\\\.)*+)\"").matcher(json);
    assertThat(matcher.find()).as("the JSON line has a " + name + " field").isTrue();
    return matcher.group(1);
  }

  /** A value the key=value rule masks in a message is masked the same in the stack trace. */
  @Test
  void maskASecretInsideAnExceptionsMessageInTheStackTraceToo() {
    String secret = "Cell-Kv-not-real";
    context
        .getLogger("org.opentmf.cadenzaflow.Cell")
        .error("login failed password=" + secret, new IllegalStateException("password=" + secret));
    assertThat(context.getStatusManager().getCopyOfStatusList())
        .noneMatch(status -> status.getLevel() == Status.ERROR);
    String json = captured.toString(StandardCharsets.UTF_8);
    assertThat(field(json, "message")).isEqualTo("login failed password=****");
    assertThat(field(json, "stack_trace")).contains("password=****").doesNotContain(secret);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "calling http://cell-user:" + PASSWORD + "@localhost:8080/engine-rest failed",
        "calling https://cell-user:" + PASSWORD + "@db.example.internal/engine-rest failed"
      })
  void maskThePasswordInsideAnAddress(String message) {
    String json = logged(message);
    assertThat(json).as("the line reached the JSON appender").contains("\"message\":\"calling ");
    assertThat(json).doesNotContain(PASSWORD);
  }
}
