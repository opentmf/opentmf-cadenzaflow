package org.opentmf.cadenzaflow.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

/**
 * Proves that the shipped {@code logback-spring.xml} actually attaches an appender to the root
 * logger, in every shape the deployment can ask for.
 *
 * <p>This exists because of a defect that survived from 1.0.0 to 1.2.1 and was invisible to every
 * other test: the file selected its appender with {@code <if condition='isDefined(…)'>}, and
 * logback 1.5's {@code IfModelHandler} ignores the {@code condition} ATTRIBUTE - it branches only
 * when a {@code <condition class="…"/>} ELEMENT has pushed a BranchState. Neither branch ran, the
 * root logger ended up with an EMPTY appender list, and the service logged nothing at all. The
 * only trace was two status lines that nobody reads on a healthy-looking pod. It was not the
 * well-known missing-janino case either: janino is on the runtime classpath and the config still
 * failed.
 *
 * <p>So the assertion here is deliberately the crude one - <em>is anything attached to root</em> -
 * rather than a check of which appender won. A test that only compared appender names would have
 * passed on the broken file just as happily, because it never looked at the empty list.
 */
@DisplayName("logback-spring.xml appender selection")
class LogbackAppenderSelectionTests {

  private static final String SELECTOR = "LOGGING_APPENDER";

  private LoggerContext context;

  @AfterEach
  void stopContextAndClearSelector() {
    if (context != null) {
      context.stop();
    }
    System.clearProperty(SELECTOR);
  }

  private List<String> rootAppenderNamesWith(String selector) throws Exception {
    if (selector == null) {
      System.clearProperty(SELECTOR);
    } else {
      System.setProperty(SELECTOR, selector);
    }
    context = new LoggerContext();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    try (InputStream configuration = getClass().getResourceAsStream("/logback-spring.xml")) {
      assertThat(configuration).as("logback-spring.xml must ship on the classpath").isNotNull();
      configurator.doConfigure(configuration);
    }

    List<String> names = new ArrayList<>();
    Iterator<Appender<ILoggingEvent>> appenders =
        context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
    while (appenders.hasNext()) {
      names.add(appenders.next().getName());
    }
    return names;
  }

  @ParameterizedTest(name = "LOGGING_APPENDER={0}")
  @ValueSource(strings = {"JSON", "CONSOLE"})
  @DisplayName("the root logger always ends up with an appender")
  void attachTheSelectedAppenderToRoot(String selector) throws Exception {
    assertThat(rootAppenderNamesWith(selector))
        .as("root must never be left with an empty appender list")
        .isNotEmpty()
        .containsExactly(selector);
  }

  @Test
  @DisplayName("an unset selector falls back to the console rather than to nothing")
  void fallBackToConsoleWhenTheSelectorIsUnset() throws Exception {
    // The developer-machine path. The old file reached this case through the <else> branch that
    // never executed; the fallback now lives in the variable default, where it cannot not fire.
    assertThat(rootAppenderNamesWith(null))
        .as("an unconfigured run must still log")
        .containsExactly("CONSOLE");
  }

  @Test
  @DisplayName("the masked JSON appender is the one the images select")
  void selectTheMaskedJsonAppenderForImages() throws Exception {
    // Both Dockerfiles set LOGGING_APPENDER=JSON. If that appender is ever renamed in
    // logback-masking.xml this fails here rather than in a cluster.
    assertThat(rootAppenderNamesWith("JSON")).containsExactly("JSON");
  }
}
