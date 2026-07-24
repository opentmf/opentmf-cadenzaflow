package org.opentmf.cadenzaflow.config.script;

import java.util.Locale;
import java.util.Set;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import org.cadenzaflow.bpm.engine.impl.scripting.engine.ScriptEngineResolver;

/**
 * A {@link ScriptEngineResolver} that answers every JavaScript language name with the singleton
 * {@link ClosingGraalJsScriptEngine} facade and delegates all other languages (JUEL, Groovy, ...)
 * to the default resolver untouched.
 */
public class ClosingGraalJsScriptEngineResolver implements ScriptEngineResolver {

  /**
   * Lower-cased names under which Camunda and the GraalJS engine factory expose the JavaScript
   * engine: {@code scriptFormat} values, {@code ScriptingEngines.DEFAULT_JS_SCRIPTING_LANGUAGE}
   * ("Graal.js"), and the factory short name "js".
   */
  private static final Set<String> JS_LANGUAGE_NAMES =
      Set.of("javascript", "ecmascript", "js", "graal.js");

  private final ScriptEngineResolver delegate;
  private final ClosingGraalJsScriptEngine graalJsScriptEngine;

  public ClosingGraalJsScriptEngineResolver(
      ScriptEngineResolver delegate, ClosingGraalJsScriptEngine graalJsScriptEngine) {
    this.delegate = delegate;
    this.graalJsScriptEngine = graalJsScriptEngine;
  }

  @Override
  public ScriptEngine getScriptEngine(String language, boolean resolveFromCache) {
    if (language != null && JS_LANGUAGE_NAMES.contains(language.toLowerCase(Locale.ROOT))) {
      return graalJsScriptEngine;
    }
    return delegate.getScriptEngine(language, resolveFromCache);
  }

  @Override
  public void addScriptEngineFactory(ScriptEngineFactory scriptEngineFactory) {
    delegate.addScriptEngineFactory(scriptEngineFactory);
  }

  @Override
  public ScriptEngineManager getScriptEngineManager() {
    return delegate.getScriptEngineManager();
  }
}
