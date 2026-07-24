package org.opentmf.cadenzaflow.config.script;

import java.util.List;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import org.cadenzaflow.bpm.engine.delegate.VariableScope;
import org.cadenzaflow.bpm.engine.impl.scripting.ExecutableScript;
import org.cadenzaflow.bpm.engine.impl.scripting.ScriptFactory;
import org.cadenzaflow.bpm.engine.impl.scripting.engine.ScriptingEngines;
import org.cadenzaflow.bpm.engine.impl.scripting.env.ScriptEnvResolver;
import org.cadenzaflow.bpm.engine.impl.scripting.env.ScriptingEnvironment;

/**
 * A {@link ScriptingEnvironment} that scopes the GraalJS polyglot context of a script invocation
 * to the whole invocation. Camunda evaluates the environment scripts (e.g. Spin's {@code S(...)}
 * helpers) and the user script as separate {@code eval} calls sharing one {@link Bindings}; the
 * context created by the first call must stay open until the last call has finished, and is
 * closed here once the invocation completes.
 */
public class ClosingScriptingEnvironment extends ScriptingEnvironment {

  public ClosingScriptingEnvironment(
      ScriptFactory scriptFactory,
      List<ScriptEnvResolver> scriptEnvResolvers,
      ScriptingEngines scriptingEngines) {
    super(scriptFactory, scriptEnvResolvers, scriptingEngines);
  }

  @Override
  public Object execute(
      ExecutableScript script, VariableScope scope, Bindings bindings, ScriptEngine scriptEngine) {
    if (!(scriptEngine instanceof ClosingGraalJsScriptEngine closingEngine)) {
      return super.execute(script, scope, bindings, scriptEngine);
    }
    closingEngine.beginInvocation();
    try {
      return super.execute(script, scope, bindings, scriptEngine);
    } finally {
      closingEngine.endInvocation(bindings);
    }
  }
}
