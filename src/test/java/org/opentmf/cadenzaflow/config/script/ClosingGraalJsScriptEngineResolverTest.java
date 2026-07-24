package org.opentmf.cadenzaflow.config.script;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import org.cadenzaflow.bpm.engine.impl.scripting.engine.ScriptEngineResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClosingGraalJsScriptEngineResolverTest {

  private final ScriptEngineResolver delegate = mock(ScriptEngineResolver.class);
  private final ClosingGraalJsScriptEngine graalJsEngine =
      new ClosingGraalJsScriptEngine(true, false, false, new SimpleMeterRegistry());
  private final ClosingGraalJsScriptEngineResolver resolver =
      new ClosingGraalJsScriptEngineResolver(delegate, graalJsEngine);

  @AfterEach
  void closeEngine() {
    graalJsEngine.close();
  }

  @Test
  void javascriptLanguageNamesResolveToClosingFacade() {
    for (String language :
        List.of("javascript", "JavaScript", "ecmascript", "js", "graal.js", "Graal.js")) {
      assertSame(graalJsEngine, resolver.getScriptEngine(language, true), language);
      assertSame(graalJsEngine, resolver.getScriptEngine(language, false), language);
    }
    verifyNoInteractions(delegate);
  }

  @Test
  void otherLanguagesAreDelegatedUntouched() {
    ScriptEngine juelEngine = mock(ScriptEngine.class);
    when(delegate.getScriptEngine("juel", true)).thenReturn(juelEngine);
    assertSame(juelEngine, resolver.getScriptEngine("juel", true));

    ScriptEngine groovyEngine = mock(ScriptEngine.class);
    when(delegate.getScriptEngine("groovy", false)).thenReturn(groovyEngine);
    assertSame(groovyEngine, resolver.getScriptEngine("groovy", false));
  }

  @Test
  void factoryRegistrationAndManagerAreDelegated() {
    ScriptEngineFactory factory = mock(ScriptEngineFactory.class);
    resolver.addScriptEngineFactory(factory);
    verify(delegate).addScriptEngineFactory(factory);

    ScriptEngineManager manager = new ScriptEngineManager();
    when(delegate.getScriptEngineManager()).thenReturn(manager);
    assertSame(manager, resolver.getScriptEngineManager());
  }
}
