package org.opentmf.cadenzaflow;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.repository.Deployment;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
import org.cadenzaflow.bpm.engine.runtime.ProcessInstance;
import org.cadenzaflow.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    // application-it.yml disables the management server; re-enable it on a random
    // port here so the actuator/prometheus exposure can be verified end-to-end.
    properties = "management.server.port=0")
@ActiveProfiles("it")
class OpenTmfCadenzaFlowApplicationIT {

  @Container
  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("keycloak/keycloak:26.4.1")
          .withAdminUsername("admin")
          .withAdminPassword("admin")
          .withRealmImportFile("realm/dsync-realm.json")
          // Pin the wait to the HTTP port: without forPort, HttpWaitStrategy polls an
          // arbitrary exposed port, and hitting the TLS (8443) or management (9000)
          // port fails with connection EOF forever. Keycloak with a realm import also
          // needs well over the default 60s on slower machines, hence the timeout.
          .waitingFor(
              Wait.forHttp("/realms/dsync/.well-known/openid-configuration")
                  .forPort(8080)
                  .withStartupTimeout(Duration.ofMinutes(5)));

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    var issuer = String.format("http://%s:%d/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    var admin = String.format("http://%s:%d/admin/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));

    // Resource Server (JWT) and/or client config – adapt to your app:
    r.add("plugin.identity.keycloak.keycloak-issuer-url", () -> issuer);
    r.add("plugin.identity.keycloak.keycloak-admin-url", () -> admin);
  }

  /**
   * Hand-written rather than built with {@code Bpmn.createExecutableProcess(...)}: the model
   * builder injects {@code camunda:historyTimeToLive="180"} of its own, which would make this
   * test pass against an engine that has no default configured at all.
   */
  private static final String BPMN_WITHOUT_HISTORY_TIME_TO_LIVE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                        targetNamespace="http://opentmf.org/ttl-default-regression">
        <bpmn:process id="ttlDefaultRegression" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="flow" sourceRef="start" targetRef="end"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /** Parks on a user task so the instance - and its variables - are still there to read. */
  private static final String BPMN_WITH_A_WAIT_STATE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                        targetNamespace="http://opentmf.org/serialization-regression">
        <bpmn:process id="serializationProbe" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="in" sourceRef="start" targetRef="wait"/>
          <bpmn:userTask id="wait"/>
          <bpmn:sequenceFlow id="out" sourceRef="wait" targetRef="end"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /** A plain POJO variable - the kind the default serialization format decides the fate of. */
  public static class Payload {
    private String name;
    private int count;

    public Payload() {
      // Jackson.
    }

    public Payload(String name, int count) {
      this.name = name;
      this.count = count;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }
  }

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private ProcessEngine processEngine;

  @LocalServerPort
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void contextLoads() {
    Assertions.assertNotNull(applicationContext);
  }

  @Test
  void engineRestWithoutTokenIsRejected() throws Exception {
    Assertions.assertEquals(401, get("/engine-rest/task", null).statusCode());
  }

  @Test
  void engineRestWithGarbageTokenIsRejected() throws Exception {
    Assertions.assertEquals(401, get("/engine-rest/task", "Bearer not-a-jwt").statusCode());
  }

  @Test
  void prometheusEndpointIsExposedOnManagementPort() throws Exception {
    var managementPort = applicationContext.getEnvironment().getProperty("local.management.port");
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
        .GET()
        .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(
        response.body().contains("jvm_memory_used_bytes"),
        "expected Prometheus exposition format with JVM metrics");
    // The engine meters are contributed by a MeterBinder, which Spring Boot applies to
    // the registry for us. Nothing else would notice if that wiring stopped working -
    // the endpoint would still answer 200 with the JVM families alone.
    Assertions.assertTrue(
        response.body().contains("cadenzaflow_engine_"),
        "expected the engine meters on the scrape endpoint, not just the JVM families");
  }

  @Test
  void managementEnvEndpointStillRequiresToken() throws Exception {
    var managementPort = applicationContext.getEnvironment().getProperty("local.management.port");
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + managementPort + "/actuator/env"))
        .GET()
        .build();
    Assertions.assertEquals(
        401, httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void webUiIsHandledBySsoRatherThanTheWhitelist() throws Exception {
    // The UI paths are NOT whitelisted in config-security.yml - an unauthenticated
    // browser is redirected by the OAuth2 login chain instead. Proven rather than
    // assumed: with the (now removed) /cadenzaflow/** whitelist entry present or
    // absent, these answer 302 either way. That entry only ever matched a path space
    // nothing serves, so its removal changed 404 into 401 there and nothing else.
    Assertions.assertEquals(302, get("/app/cockpit", null).statusCode());
    Assertions.assertEquals(302, get("/api/admin/auth/user/default", null).statusCode());
  }

  @Test
  void unmatchedApplicationPortPathIsDenied() throws Exception {
    // SEC-02: unmatched paths are denied, not allowed. Guards the whitelist against
    // regaining a blanket entry that would turn this into a 404 (i.e. "reached the
    // app") for anything under the context path.
    Assertions.assertEquals(401, get("/cadenzaflow/anything", null).statusCode());
  }

  @Test
  void whitelistedExternalTaskEndpointBypassesRbac() throws Exception {
    // /engine-rest/external-task/** is whitelisted in config-security.yml, so the
    // request must reach the engine REST layer instead of being rejected with 401.
    Assertions.assertEquals(200, get("/engine-rest/external-task/count", null).statusCode());
  }

  @Test
  void acceptsProcessDefinitionWithoutHistoryTimeToLiveAndAppliesTheConfiguredDefault() {
    // Since 7.20 the engine REJECTS a definition that states no historyTimeToLive, at parse
    // time, unless a default is configured - so `historyTimeToLive: P30D` in
    // config-cadenzaflow.yml is what makes such a deployment possible at all. Remove it and
    // every deployer who omits the attribute gets ENGINE-09005. The exact value is asserted
    // too, because the 1.1.0 release notes promise a 30-day default.
    Deployment deployment = processEngine.getRepositoryService()
        .createDeployment()
        .name("ttl-default-regression")
        .addString("ttlDefaultRegression.bpmn20.xml", BPMN_WITHOUT_HISTORY_TIME_TO_LIVE)
        .deploy();
    try {
      ProcessDefinition definition = processEngine.getRepositoryService()
          .createProcessDefinitionQuery()
          .deploymentId(deployment.getId())
          .singleResult();
      Assertions.assertNotNull(definition, "the TTL-less definition was not deployed");
      Assertions.assertEquals(
          30,
          definition.getHistoryTimeToLive(),
          "expected the configured P30D default to be applied to a definition without one");
    } finally {
      processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  @Test
  void storesObjectVariablesAsJsonRatherThanJavaSerializedBlobs() {
    // Asserted on a stored variable rather than on the configuration property, because
    // the property is only interesting if it reaches the variable: the engine's own
    // default is application/x-java-serialized-object, which is unreadable to Cockpit
    // and to every non-Java consumer. Reading it back SERIALIZED (deserialized=false)
    // is what exposes the format actually used on the way in.
    Deployment deployment = processEngine.getRepositoryService()
        .createDeployment()
        .name("serialization-regression")
        .addString("serializationProbe.bpmn20.xml", BPMN_WITH_A_WAIT_STATE)
        .deploy();
    try {
      ProcessInstance instance = processEngine.getRuntimeService()
          .startProcessInstanceByKey("serializationProbe",
              Map.of("payload", new Payload("probe", 7)));

      ObjectValue stored = processEngine.getRuntimeService()
          .getVariableTyped(instance.getId(), "payload", false);

      Assertions.assertEquals(
          "application/json",
          stored.getSerializationDataFormat(),
          "object variables must default to JSON, not a Java-serialized blob");
      // Substance, not an exact string: field ORDER is Jackson's business and a change
      // there is not a regression, whereas losing readable JSON is.
      Assertions.assertTrue(
          stored.getValueSerialized().contains("\"name\":\"probe\"")
              && stored.getValueSerialized().contains("\"count\":7"),
          "the stored form should be readable JSON, was: " + stored.getValueSerialized());
    } finally {
      processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path))
        .GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
