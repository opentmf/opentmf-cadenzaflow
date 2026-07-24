package org.opentmf.cadenzaflow.config.script;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.cadenzaflow.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.springframework.stereotype.Component;

/**
 * Replaces the engine's JavaScript scripting infrastructure with the context-closing GraalJS
 * facade, fixing the unbounded polyglot-context leak of the stock JSR-223 bridge (one leaked
 * context per script evaluation, pinned forever by the Truffle engine registry).
 */
@Component
public class ScriptEnginePlugin extends AbstractProcessEnginePlugin {

  private final MeterRegistry meterRegistry;
  private ClosingGraalJsScriptEngine scriptEngine;

  public ScriptEnginePlugin(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl configuration) {
    scriptEngine =
        new ClosingGraalJsScriptEngine(
            configuration.isConfigureScriptEngineHostAccess(),
            configuration.isEnableScriptEngineLoadExternalResources(),
            configuration.isEnableScriptEngineNashornCompatibility(),
            meterRegistry);
    ClosingGraalJsScriptEngineResolver resolver =
        new ClosingGraalJsScriptEngineResolver(
            configuration.getScriptEngineResolver(), scriptEngine);
    configuration.setScriptEngineResolver(resolver);
    configuration.getScriptingEngines().setScriptEngineResolver(resolver);
    configuration.setScriptingEnvironment(
        new ClosingScriptingEnvironment(
            configuration.getScriptFactory(),
            configuration.getEnvScriptResolvers(),
            configuration.getScriptingEngines()));
    // Process-application-scoped resolution would bypass the resolver above with a fresh (leaky)
    // default resolver. In this single-classloader Spring Boot deployment the PA classloader is
    // the application classloader, so global resolution is equivalent.
    configuration.setEnableFetchScriptEngineFromProcessApplication(false);
  }

  @PreDestroy
  public void shutdown() {
    if (scriptEngine != null) {
      scriptEngine.close();
    }
  }
}
