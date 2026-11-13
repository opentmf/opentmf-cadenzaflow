package org.opentmf.cadenzaflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.awaitility.Awaitility;
import org.cadenzaflow.bpm.engine.ExternalTaskService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.cadenzaflow.bpm.engine.externaltask.LockedExternalTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CF-INCIDENT-SELECTOR-NULL-IS-NOT-UNSCOPED-1: a group's {@code selector}, posted back
 * verbatim, retries that one group only - also when a key it echoes is NULL.
 *
 * <p>The report groups on the incident's tenant and on its caller, so a null tenant and a
 * root-level (caller-less) instance are groups of their own. Each test deploys its own
 * definition key twice - without a tenant and for {@code tenant-a} - and raises four
 * originating incidents on ONE activity: {no tenant, tenant-a} x {root-level, called}.
 * The definition calls ITSELF once ({@code nested=true}), so a root-level and a called
 * group share root, definition, version and activity and differ only in the caller.
 * The verbatim tests post one group's selector back and expect exactly that group's one
 * incident, the other three groups untouched; the marker tests write the request by hand
 * (Cezmi's ruling: an omitted key still means any, {@code withoutTenant} and
 * {@code rootLevelOnly} say "none", and the report emits them).</p>
 *
 * <p>Boot as {@link IncidentOperationsIT}: Testcontainers PostgreSQL via the datasource
 * URL, Keycloak for the identity plugin, role-bearing tokens minted in-test.</p>
 *
 * @author Cezmi Aslan
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class IncidentSelectorScopeIT {

  private static final String AUDIENCE = "it-client";
  private static final String WORKER = "it-worker";
  private static final String TENANT = "tenant-a";
  private static final String ACTIVITY = "doWork";
  private static final String CALL_ACTIVITY = "nestedCall";

  @Container
  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("keycloak/keycloak:26.4.1")
          .withAdminUsername("admin")
          .withAdminPassword("admin")
          .withRealmImportFile("realm/dsync-realm.json")
          .waitingFor(
              Wait.forHttp("/realms/dsync/.well-known/openid-configuration")
                  .forPort(8080)
                  .withStartupTimeout(ItContainers.STARTUP_TIMEOUT));

  /** Minimal OIDC issuer stand-in: RSA key, file-served JWKS, per-test token minting. */
  static final class TestIssuer {
    final String issuer = "http://issuer.test/realms/it";
    final RSAKey key;
    final String jwkSetUri;

    TestIssuer() {
      try {
        this.key = new RSAKeyGenerator(2048).keyID("it-key").generate();
        Path jwks = Files.createTempFile("jwks", ".json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
        this.jwkSetUri = jwks.toUri().toString();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    String mint(String email, List<String> roles) {
      try {
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("subject-id")
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email", email)
                .claim("roles", roles)
                .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  static final TestIssuer ISSUER = new TestIssuer();

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    var issuer = String.format("http://%s:%d/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    var admin = String.format("http://%s:%d/admin/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    r.add("plugin.identity.keycloak.keycloak-issuer-url", () -> issuer);
    r.add("plugin.identity.keycloak.keycloak-admin-url", () -> admin);
    r.add("opentmf.security.jwk-set-uri", () -> "");
    r.add("opentmf.security.issuers[0].name", () -> "it");
    r.add("opentmf.security.issuers[0].issuer", () -> ISSUER.issuer);
    r.add("opentmf.security.issuers[0].jwk-set-uri", () -> ISSUER.jwkSetUri);
    r.add("opentmf.security.issuers[0].user-claim", () -> "email");
    r.add("opentmf.security.issuers[0].authorities-claim", () -> "roles");
    r.add("opentmf.security.issuers[0].audiences[0]", () -> AUDIENCE);
  }

  /**
   * start -> nested? -> call ITSELF once (the called instance gets nested=false) : the
   * external task. Topic = key, so each test fails only its own tasks.
   */
  private static String flow(String key) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                          targetNamespace="http://opentmf.org/incident-selector-scope">
          <bpmn:process id="%1$s" name="Scope flow" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="in" sourceRef="start" targetRef="route"/>
            <bpmn:exclusiveGateway id="route"/>
            <bpmn:sequenceFlow id="toCall" sourceRef="route" targetRef="%2$s">
              <bpmn:conditionExpression>${nested}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="toWork" sourceRef="route" targetRef="%3$s">
              <bpmn:conditionExpression>${!nested}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:callActivity id="%2$s" calledElement="%1$s">
              <bpmn:extensionElements>
                <camunda:in sourceExpression="${false}" target="nested"/>
              </bpmn:extensionElements>
            </bpmn:callActivity>
            <bpmn:sequenceFlow id="afterCall" sourceRef="%2$s" targetRef="end"/>
            <bpmn:serviceTask id="%3$s" name="Do work" camunda:type="external" camunda:topic="%1$s"/>
            <bpmn:sequenceFlow id="afterWork" sourceRef="%3$s" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(key, CALL_ACTIVITY, ACTIVITY);
  }

  @Autowired
  private ProcessEngine processEngine;

  @LocalServerPort
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String READER_TOKEN =
      ISSUER.mint("reader@it.test", List.of("ebu-federation-reader"));
  private static final String WRITER_TOKEN =
      ISSUER.mint("writer@it.test", List.of("ebu-federation-writer"));

  @Test
  void theFourGroupsAreReportedApart() throws Exception {
    List<JsonNode> groups = fourGroups("scopeReport");

    assertThat(groups).hasSize(4).allSatisfy(group -> {
      assertThat(group.get("incidentCount").asLong()).isEqualTo(1);
      assertThat(group.get("activityId").asText()).isEqualTo(ACTIVITY);
      assertThat(group.get("processDefinitionVersion").asInt()).isEqualTo(1);
    });
  }

  @Test
  void aCalledGroupOfOneTenantStaysInsideItsGroup() throws Exception {
    // Control: both keys echoed with a value. Green before the row.
    assertVerbatimRetryTouchesOnlyItsGroup("scopeTenantCalled", TENANT, true);
  }

  @Test
  void aNullTenantSelectorStaysOutOfTheOtherTenant() throws Exception {
    // Tenant echoed as null, caller echoed with a value. Before the row the null tenant
    // drops the tenant filter and tenant-a's called incident is retried with it.
    assertVerbatimRetryTouchesOnlyItsGroup("scopeNullTenant", null, true);
  }

  @Test
  void aRootLevelSelectorStaysOutOfTheCalledGroup() throws Exception {
    // Tenant echoed with a value, caller echoed as null. Before the row the null caller
    // drops the caller filter and the called incident of the same activity is retried.
    assertVerbatimRetryTouchesOnlyItsGroup("scopeRootLevel", TENANT, false);
  }

  @Test
  void aSelectorWithBothKeysNullTouchesOnlyItsOwnIncident() throws Exception {
    // Both echoed as null: before the row this retries all four groups.
    assertVerbatimRetryTouchesOnlyItsGroup("scopeBothNull", null, false);
  }

  @Test
  void theReportMarksTheGroupsWithoutTenantOrCaller() throws Exception {
    List<JsonNode> groups = fourGroups("scopeMarkers");

    assertThat(groups).allSatisfy(group -> {
      JsonNode selector = group.get("selector");
      assertThat(selector.path("withoutTenant").asBoolean())
          .as("withoutTenant marks exactly the groups without a tenant")
          .isEqualTo(!selector.hasNonNull("tenantId"));
      assertThat(selector.path("rootLevelOnly").asBoolean())
          .as("rootLevelOnly marks exactly the root-level groups")
          .isEqualTo(!selector.hasNonNull("calledFrom"));
    });
  }

  @Test
  void anOmittedTenantAndCallerStillMeanAny() throws Exception {
    fourGroups("scopeAny");

    assertThat(handWrittenRetry("scopeAny", "")).as("no key, no marker: every group").isEqualTo(4);
  }

  @Test
  void eachMarkerOnItsOwnNarrowsToTheGroupsItNames() throws Exception {
    fourGroups("scopeEachMarker");

    assertThat(handWrittenRetry("scopeEachMarker", "\"withoutTenant\": true,"))
        .as("withoutTenant: the root-level and the called group without a tenant").isEqualTo(2);
  }

  @Test
  void rootLevelOnlyOnItsOwnNarrowsToTheRootLevelGroups() throws Exception {
    fourGroups("scopeRootOnly");

    assertThat(handWrittenRetry("scopeRootOnly", "\"rootLevelOnly\": true,"))
        .as("rootLevelOnly: the root-level group of each tenant").isEqualTo(2);
  }

  @Test
  void aMarkerBesideAValueForTheSameKeyIsRefused() throws Exception {
    assertThat(post("/engine-rest/extensions/incident/retry", retryBody("scopeRefused",
        "\"tenantId\": \"tenant-a\", \"withoutTenant\": true,"), WRITER_TOKEN).statusCode())
        .isEqualTo(400);
    assertThat(post("/engine-rest/extensions/incident/retry", retryBody("scopeRefused",
        "\"calledFrom\": {\"processDefinitionKey\": \"scopeRefused\", \"callActivityId\": \""
            + CALL_ACTIVITY + "\"}, \"rootLevelOnly\": true,"), WRITER_TOKEN).statusCode())
        .isEqualTo(400);
  }

  /** Posts a hand-written retry for {@code key} with the extra fields; the matched count. */
  private long handWrittenRetry(String key, String extraFields) throws Exception {
    HttpResponse<String> response = post("/engine-rest/extensions/incident/retry",
        retryBody(key, extraFields), WRITER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    return objectMapper.readTree(response.body()).get("incidentCount").asLong();
  }

  private static String retryBody(String key, String extraFields) {
    return """
        {
          "rootProcessDefinitionKey": "%1$s",
          "processDefinitionKey": "%1$s",
          "activityId": "%2$s",
          "incidentType": "failedExternalTask",
          %3$s
          "retries": 1
        }
        """.formatted(key, ACTIVITY, extraFields);
  }

  private void assertVerbatimRetryTouchesOnlyItsGroup(String key, String tenant, boolean called)
      throws Exception {
    List<JsonNode> groups = fourGroups(key);
    JsonNode target = groups.stream()
        .filter(group -> isGroup(group, tenant, called))
        .findFirst().orElseThrow();

    ObjectNode retry = ((ObjectNode) target.get("selector").deepCopy()).put("retries", 1);
    HttpResponse<String> response = post("/engine-rest/extensions/incident/retry",
        objectMapper.writeValueAsString(retry), WRITER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.body()).get("incidentCount").asLong())
        .as("a selector posted back verbatim retries its own group's one incident only")
        .isEqualTo(1);

    // The batch runs on the job executor; once it has, exactly the other three remain.
    Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
        .until(() -> groups(key).size() == 3);
    assertThat(groups(key))
        .noneMatch(group -> isGroup(group, tenant, called))
        .allSatisfy(group -> assertThat(group.get("incidentCount").asLong()).isEqualTo(1));
  }

  private static boolean isGroup(JsonNode group, String tenant, boolean called) {
    JsonNode tenantId = group.get("tenantId");
    boolean tenantMatches = tenant == null
        ? tenantId == null || tenantId.isNull()
        : tenantId != null && tenant.equals(tenantId.asText());
    JsonNode calledFrom = group.get("calledFrom");
    boolean callerMatches = called == (calledFrom != null && !calledFrom.isNull());
    return tenantMatches && callerMatches;
  }

  /** Deploys {@code key} without a tenant and for tenant-a, raises the four incidents. */
  private List<JsonNode> fourGroups(String key) throws Exception {
    processEngine.getRepositoryService().createDeployment()
        .name(key).addString(key + ".bpmn20.xml", flow(key)).deploy();
    processEngine.getRepositoryService().createDeployment()
        .name(key + "-" + TENANT).tenantId(TENANT)
        .addString(key + ".bpmn20.xml", flow(key)).deploy();

    RuntimeService runtime = processEngine.getRuntimeService();
    for (boolean nested : new boolean[] {false, true}) {
      runtime.createProcessInstanceByKey(key).processDefinitionWithoutTenantId()
          .setVariable("nested", nested).execute();
      runtime.createProcessInstanceByKey(key).processDefinitionTenantId(TENANT)
          .setVariable("nested", nested).execute();
    }

    ExternalTaskService externalTasks = processEngine.getExternalTaskService();
    List<LockedExternalTask> tasks = externalTasks.fetchAndLock(10, WORKER)
        .topic(key, 60_000L).execute();
    assertThat(tasks).as("one external task per instance that reached the work").hasSize(4);
    for (LockedExternalTask task : tasks) {
      externalTasks.handleFailure(task.getId(), WORKER, "work failed", 0, 0);
    }
    return groups(key);
  }

  private List<JsonNode> groups(String key) throws Exception {
    HttpResponse<String> response = get(
        "/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=" + key, READER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    List<JsonNode> groups = new ArrayList<>();
    objectMapper.readTree(response.body()).forEach(groups::add);
    return groups;
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return httpClient.send(request(path, token).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, String token) throws Exception {
    return httpClient.send(
        request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path, String token) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path))
        .header("Authorization", "Bearer " + token);
  }
}
