package org.opentmf.cadenzaflow.config.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClosingGraalJsScriptEngineTests {

  private static SimpleMeterRegistry meterRegistry;
  private static ClosingGraalJsScriptEngine engine;

  @BeforeAll
  static void createEngine() {
    meterRegistry = new SimpleMeterRegistry();
    engine = new ClosingGraalJsScriptEngine(true, false, false, meterRegistry);
  }

  @AfterAll
  static void closeEngine() {
    engine.close();
  }

  @Test
  void evalReturnsSameResultsAsStockGraalJsEngine() throws ScriptException {
    List<String> scripts =
        List.of(
            "1 + 2",
            "'a' + 'b'",
            "1 < 2",
            "({x: 21}).x * 2",
            "Java.type('java.lang.Integer').parseInt('42')");
    try (GraalJSScriptEngine stock = GraalJSScriptEngine.create()) {
      ScriptContext stockContext = stock.getContext();
      stockContext.setAttribute("polyglot.js.allowHostAccess", true, ScriptContext.ENGINE_SCOPE);
      stockContext.setAttribute(
          "polyglot.js.allowHostClassLookup", true, ScriptContext.ENGINE_SCOPE);
      for (String script : scripts) {
        assertEquals(stock.eval(script), engine.eval(script), script);
      }
    }
    assertCountersBalanced();
  }

  @Test
  void everyEvaluationClosesItsContext() throws ScriptException {
    double createdBefore = created();
    for (int i = 0; i < 1000; i++) {
      assertEquals(i + 1, ((Number) engine.eval("1 + " + i)).intValue());
    }
    for (int i = 0; i < 1000; i++) {
      assertEquals(42, ((Number) engine.eval("6 * 7")).intValue());
    }
    assertTrue(created() - createdBefore >= 2000);
    assertCountersBalanced();
  }

  @Test
  void compiledScriptClosesContextPerEvaluation() throws ScriptException {
    CompiledScript compiled = engine.compile("a + b");
    for (int i = 0; i < 1000; i++) {
      Bindings bindings = engine.createBindings();
      bindings.put("a", i);
      bindings.put("b", 1);
      assertEquals(i + 1, ((Number) compiled.eval(bindings)).intValue());
    }
    assertCountersBalanced();
  }

  @Test
  void objectResultsRemainUsableAfterContextClose() throws ScriptException {
    Object result = engine.eval("({name: 'camunda', items: [1, 2, 3]})");
    Map<?, ?> map = assertInstanceOf(Map.class, result);
    assertEquals("camunda", map.get("name"));
    List<?> items = assertInstanceOf(List.class, map.get("items"));
    assertEquals(List.of(1, 2, 3), items);
    assertCountersBalanced();
  }

  @Test
  void bindingsVariablesAreVisibleToTheScript() throws ScriptException {
    Bindings bindings = engine.createBindings();
    bindings.put("x", 5);
    assertEquals(10, ((Number) engine.eval("x * 2", bindings)).intValue());
    assertCountersBalanced();
  }

  @Test
  void hostInteropWorks() throws ScriptException {
    Object size =
        engine.eval("var list = new (Java.type('java.util.ArrayList'))(); list.add('x'); list.size()");
    assertEquals(1, ((Number) size).intValue());
    assertCountersBalanced();
  }

  @Test
  void syntaxErrorSurfacesAtCompileTime() {
    assertThrows(ScriptException.class, () -> engine.compile("function ("));
    assertCountersBalanced();
  }

  @Test
  void readerBasedEvalAndCompileWork() throws ScriptException {
    assertEquals(7, ((Number) engine.eval(new StringReader("3 + 4"))).intValue());

    CompiledScript compiled = engine.compile(new StringReader("a * 2"));
    Bindings bindings = engine.createBindings();
    bindings.put("a", 21);
    assertEquals(42, ((Number) compiled.eval(bindings)).intValue());
    assertCountersBalanced();
  }

  @Test
  void failingReaderSurfacesAsScriptException() {
    Reader failing =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("boom");
          }

          @Override
          public void close() {
            // nothing to release
          }
        };
    assertThrows(ScriptException.class, () -> engine.eval(failing));
  }

  @Test
  void nestedInvocationScopesCloseTheirOwnContexts() throws ScriptException {
    Bindings outer = engine.createBindings();
    Bindings inner = engine.createBindings();
    engine.beginInvocation();
    try {
      engine.eval("var marker = 'outer';", outer);
      engine.beginInvocation();
      try {
        assertEquals(2, ((Number) engine.eval("1 + 1", inner)).intValue());
      } finally {
        engine.endInvocation(inner);
      }
      // outer context survived the nested invocation
      assertEquals("outer", engine.eval("marker", outer));
    } finally {
      engine.endInvocation(outer);
    }
    assertCountersBalanced();
  }

  @Test
  void closableBindingsOfTheStockEngineAreNeverProbed() {
    try (GraalJSScriptEngine stock = GraalJSScriptEngine.create()) {
      Bindings graalBindings = stock.createBindings();
      engine.beginInvocation();
      // GraalJSBindings allocate a context when read; endInvocation must not touch them
      engine.endInvocation(graalBindings);
      assertCountersBalanced();
    }
  }

  @Test
  void externalResourceAndNashornCompatibilityFlagsAreApplied() throws ScriptException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (ClosingGraalJsScriptEngine permissiveEngine =
        new ClosingGraalJsScriptEngine(true, true, true, registry)) {
      assertEquals(3, ((Number) permissiveEngine.eval("1 + 2")).intValue());
    }
  }

  @Test
  void invocationScopeKeepsContextOpenAcrossEnvironmentAndUserScript() throws ScriptException {
    Bindings bindings = engine.createBindings();
    engine.beginInvocation();
    try {
      // like a Spin environment script: defines a function for the user script
      engine.eval("function greet(name) { return 'hi ' + name; }", bindings);
      assertEquals("hi js", engine.eval("greet('js')", bindings));
    } finally {
      engine.endInvocation(bindings);
    }
    assertCountersBalanced();
  }

  private static double created() {
    return meterRegistry
        .get(ClosingGraalJsScriptEngine.CONTEXTS_CREATED_METRIC)
        .counter()
        .count();
  }

  private static double closed() {
    return meterRegistry
        .get(ClosingGraalJsScriptEngine.CONTEXTS_CLOSED_METRIC)
        .counter()
        .count();
  }

  private static void assertCountersBalanced() {
    assertEquals(created(), closed(), "every created polyglot context must be closed");
  }
}
