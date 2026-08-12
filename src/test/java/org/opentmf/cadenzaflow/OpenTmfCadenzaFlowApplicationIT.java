package org.opentmf.cadenzaflow;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

  @Autowired
  private ApplicationContext applicationContext;

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
