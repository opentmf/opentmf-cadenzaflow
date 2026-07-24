package org.opentmf.cadenzaflow.config.script;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

/**
 * A stateless JSR-223 facade over {@link GraalJSScriptEngine} that closes every polyglot {@link
 * Context} once the evaluation that created it has finished.
 *
 * <p>Camunda caches the {@link ScriptEngine} but supplies fresh {@link Bindings} for every
 * evaluation, and the stock {@link GraalJSScriptEngine} allocates a new polyglot {@link Context}
 * per fresh bindings. The {@code javax.script} API has no lifecycle, so nothing ever calls {@link
 * Context#close()}, while the Truffle engine registry keeps a strong reference to every context
 * ever created — the old generation grows monotonically with the number of script evaluations and
 * a full GC reclaims nothing.
 *
 * <p>A context created by an evaluation is closed as soon as that evaluation returns, unless
 * {@link #beginInvocation()} marked the current thread as being inside a Camunda script
 * invocation. Camunda evaluates environment scripts (e.g. Spin's {@code S(...)} helpers) and the
 * user script as separate {@code eval} calls sharing one {@link Bindings}, so during an invocation
 * the close is deferred to {@link #endInvocation(Bindings)} — otherwise the definitions made by
 * the environment scripts would die with their context before the user script runs.
 *
 * <p>All contexts share one polyglot {@link Engine}, which caches compiled sources, so per-eval
 * context creation does not recompile unchanged scripts.
 */
public class ClosingGraalJsScriptEngine extends AbstractScriptEngine
    implements Compilable, AutoCloseable {

  /** The binding key under which {@link GraalJSScriptEngine} stores the context it creates. */
  static final String POLYGLOT_CONTEXT_BINDING = "polyglot.context";

  static final String CONTEXTS_CREATED_METRIC = "opentmf.graaljs.contexts.created";
  static final String CONTEXTS_CLOSED_METRIC = "opentmf.graaljs.contexts.closed";

  private static final String MAGIC_OPTION_PREFIX = "polyglot.js.";

  private static final ThreadLocal<Integer> INVOCATION_DEPTH = ThreadLocal.withInitial(() -> 0);

  private final Engine sharedEngine;
  private final ScriptEngineFactory factory;
  private final boolean configureHostAccess;
  private final boolean allowLoadExternalResources;
  private final boolean nashornCompatibility;
  private final Counter contextsCreated;
  private final Counter contextsClosed;

  public ClosingGraalJsScriptEngine(
      boolean configureHostAccess,
      boolean allowLoadExternalResources,
      boolean nashornCompatibility,
      MeterRegistry meterRegistry) {
    this.configureHostAccess = configureHostAccess;
    this.allowLoadExternalResources = allowLoadExternalResources;
    this.nashornCompatibility = nashornCompatibility;
    this.sharedEngine =
        Engine.newBuilder()
            .allowExperimentalOptions(true)
            // Interpreter-only execution is intentional: as of GraalVM 25, in-process JIT of
            // guest code requires a GraalVM JDK, which this deployment does not ship. If the
            // runtime ever supports it, the JIT engages regardless of this warning option.
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    // createDelegate, not a bare create: closing materializes a default context on the shared
    // engine, and all contexts of a shared engine must use the same host access configuration
    try (GraalJSScriptEngine template = createDelegate()) {
      this.factory = template.getFactory();
    }
    this.contextsCreated =
        Counter.builder(CONTEXTS_CREATED_METRIC)
            .description("GraalJS polyglot contexts created for script evaluations")
            .register(meterRegistry);
    this.contextsClosed =
        Counter.builder(CONTEXTS_CLOSED_METRIC)
            .description("GraalJS polyglot contexts closed after script evaluations")
            .register(meterRegistry);
  }

  @Override
  public Object eval(String script, ScriptContext scriptContext) throws ScriptException {
    // the delegate is deliberately not closed: that would allocate (only to close) a default
    // context this engine never uses; the context of this evaluation lives in the ScriptContext
    // bindings and is closed by evalAndManageContext/endInvocation
    GraalJSScriptEngine delegate = createDelegate();
    return evalAndManageContext(() -> delegate.eval(script, scriptContext), scriptContext);
  }

  @Override
  public Object eval(Reader reader, ScriptContext scriptContext) throws ScriptException {
    return eval(readFully(reader), scriptContext);
  }

  @Override
  public CompiledScript compile(String script) throws ScriptException {
    checkSyntax(script);
    // Camunda caches this object per process definition, but only the source string is retained
    // here - compiled-code reuse across evaluations happens in the shared engine's source cache
    return new CompiledScript() {
      @Override
      public Object eval(ScriptContext scriptContext) throws ScriptException {
        return ClosingGraalJsScriptEngine.this.eval(script, scriptContext);
      }

      @Override
      public ScriptEngine getEngine() {
        return ClosingGraalJsScriptEngine.this;
      }
    };
  }

  @Override
  public CompiledScript compile(Reader reader) throws ScriptException {
    return compile(readFully(reader));
  }

  @Override
  public Bindings createBindings() {
    // Deliberately not GraalJSBindings: those lazily allocate a polyglot context of their own.
    return new SimpleBindings();
  }

  @Override
  public ScriptEngineFactory getFactory() {
    return factory;
  }

  /**
   * Marks the current thread as being inside a Camunda script invocation, deferring context
   * closing to {@link #endInvocation(Bindings)}.
   */
  void beginInvocation() {
    INVOCATION_DEPTH.set(INVOCATION_DEPTH.get() + 1);
  }

  /** Ends the invocation scope and closes the context the invocation created, if any. */
  void endInvocation(Bindings bindings) {
    int depth = INVOCATION_DEPTH.get() - 1;
    if (depth <= 0) {
      INVOCATION_DEPTH.remove();
    } else {
      INVOCATION_DEPTH.set(depth);
    }
    closePolyglotContext(bindings);
  }

  @Override
  public void close() {
    sharedEngine.close();
  }

  /**
   * Creates a throwaway delegate engine whose context configuration mirrors what Camunda's
   * {@code DefaultScriptEngineResolver} applies to the stock engine: the magic {@code
   * polyglot.js.*} bindings entries mutate the delegate's context builder before any context
   * exists, exactly like {@code configureGraalJsScriptEngine} does.
   */
  private GraalJSScriptEngine createDelegate() {
    GraalJSScriptEngine delegate = GraalJSScriptEngine.create(sharedEngine, null);
    Bindings defaultBindings = delegate.getBindings(ScriptContext.ENGINE_SCOPE);
    if (configureHostAccess) {
      defaultBindings.put(MAGIC_OPTION_PREFIX + "allowHostAccess", true);
      defaultBindings.put(MAGIC_OPTION_PREFIX + "allowHostClassLookup", true);
    }
    if (allowLoadExternalResources) {
      defaultBindings.put(MAGIC_OPTION_PREFIX + "allowIO", true);
    }
    if (nashornCompatibility) {
      defaultBindings.put(MAGIC_OPTION_PREFIX + "nashorn-compat", true);
    }
    return delegate;
  }

  private Object evalAndManageContext(Evaluation evaluation, ScriptContext scriptContext)
      throws ScriptException {
    Bindings engineBindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
    boolean hadContext = findPolyglotContext(engineBindings) != null;
    try {
      return copyToJava(evaluation.evaluate());
    } finally {
      if (!hadContext && findPolyglotContext(engineBindings) != null) {
        contextsCreated.increment();
      }
      if (INVOCATION_DEPTH.get() == 0) {
        closePolyglotContext(engineBindings);
      }
    }
  }

  /**
   * Verifies the script parses, so that syntax errors still surface at compile (i.e. deployment)
   * time like with the stock engine. The delegate's default context performs the parse and is
   * closed right away.
   */
  private void checkSyntax(String script) throws ScriptException {
    GraalJSScriptEngine delegate = createDelegate();
    contextsCreated.increment();
    try {
      delegate.compile(script);
    } finally {
      delegate.close();
      contextsClosed.increment();
    }
  }

  private Context findPolyglotContext(Bindings bindings) {
    // GraalJSBindings, the only AutoCloseable Bindings in play, allocate a context when read -
    // never probe them, their owner is responsible for closing
    if (bindings == null || bindings instanceof AutoCloseable) {
      return null;
    }
    return bindings.get(POLYGLOT_CONTEXT_BINDING) instanceof Context polyglotContext
        ? polyglotContext
        : null;
  }

  private void closePolyglotContext(Bindings bindings) {
    Context polyglotContext = findPolyglotContext(bindings);
    if (polyglotContext != null) {
      bindings.remove(POLYGLOT_CONTEXT_BINDING);
      polyglotContext.close();
      contextsClosed.increment();
    }
  }

  /**
   * Detaches an evaluation result from its (about to be closed) context. The JSR-223 bridge
   * returns JS objects and arrays as polyglot proxies that throw once the context is closed, so
   * they are copied into plain Java maps and lists while the context is still alive. Genuine Java
   * objects pass through untouched.
   */
  private static Object copyToJava(Object value) {
    if (isPolyglotProxy(value)) {
      if (value instanceof Map<?, ?> map) {
        Map<Object, Object> copy = LinkedHashMap.newLinkedHashMap(map.size());
        map.forEach((key, entry) -> copy.put(copyToJava(key), copyToJava(entry)));
        return copy;
      }
      if (value instanceof List<?> list) {
        List<Object> copy = new ArrayList<>(list.size());
        list.forEach(element -> copy.add(copyToJava(element)));
        return copy;
      }
    }
    return value;
  }

  private static boolean isPolyglotProxy(Object value) {
    return value != null && value.getClass().getName().startsWith("com.oracle.truffle.polyglot.");
  }

  private static String readFully(Reader reader) throws ScriptException {
    StringBuilder builder = new StringBuilder();
    char[] buffer = new char[8192];
    try (Reader source = reader) {
      int count;
      while ((count = source.read(buffer)) != -1) {
        builder.append(buffer, 0, count);
      }
    } catch (IOException e) {
      throw new ScriptException(e);
    }
    return builder.toString();
  }

  @FunctionalInterface
  private interface Evaluation {
    Object evaluate() throws ScriptException;
  }
}
