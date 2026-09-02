package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The prefix is spliced into SQL text (a schema cannot be a bound parameter), so this
 * class is the one place that decides what may reach a statement.
 *
 * @author Cezmi Aslan
 */
class EngineSqlSupportTests {

  private static ProcessEngine engineWithPrefix(String prefix) {
    ProcessEngine engine = mock(ProcessEngine.class);
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(engine.getProcessEngineConfiguration()).thenReturn(configuration);
    when(configuration.getDatabaseTablePrefix()).thenReturn(prefix);
    return engine;
  }

  @ParameterizedTest
  @ValueSource(strings = {"cadenzaflow.", "public.", "", "ACT"})
  void acceptPlainSchemaPrefixes(String prefix) {
    EngineSqlSupport support = new EngineSqlSupport(engineWithPrefix(prefix));
    assertThat(support.table("ACT_RU_INCIDENT")).isEqualTo(prefix + "ACT_RU_INCIDENT");
  }

  @Test
  void treatAMissingPrefixAsEmpty() {
    EngineSqlSupport support = new EngineSqlSupport(engineWithPrefix(null));
    assertThat(support.table("ACT_RU_INCIDENT")).isEqualTo("ACT_RU_INCIDENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a;drop", "a.b.", "a b.", "a-b.", "'; --"})
  void refuseAnythingThatIsNotAPlainIdentifierAtConstruction(String prefix) {
    ProcessEngine engine = engineWithPrefix(prefix);
    assertThatIllegalStateException()
        .isThrownBy(() -> new EngineSqlSupport(engine))
        .withMessageContaining(prefix);
  }
}
