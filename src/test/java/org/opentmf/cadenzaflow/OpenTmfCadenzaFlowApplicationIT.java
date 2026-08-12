package org.opentmf.cadenzaflow;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.repository.Deployment;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
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
