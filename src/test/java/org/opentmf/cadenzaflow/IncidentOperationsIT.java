package org.opentmf.cadenzaflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.awaitility.Awaitility;
import org.cadenzaflow.bpm.engine.ExternalTaskService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.externaltask.ExternalTask;
import org.cadenzaflow.bpm.engine.externaltask.LockedExternalTask;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * End-to-end scenario for the incident operations endpoints: a parent BPMN calls a
 * child whose external task fails with no retries left, so the engine raises three
 * originating incidents and copies each into the parent instance. The grouped report
 * must collapse that propagation (3, not 6), name the group from the BPMN models, and
 * the group retry must resolve all of it through engine batches executed by the job
 * executor.
 *
 * <p>Boot mirrors {@link OpenTmfCadenzaFlowApplicationIT} (Testcontainers PostgreSQL via
 * the datasource URL, Keycloak for the identity plugin). Role-bearing tokens are minted
 * in-test against an {@code opentmf.security.issuers[]} override, the same shape as
 * {@link DualIssuerIT} — the dsync realm ships no users carrying the
 * {@code ebu-federation-*} roles the {@code it} profile's ACL names.</p>
 *
 * @author Cezmi Aslan
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
// Ordered scenario steps over one engine state; state shared between them is static,
// because JUnit creates a fresh test instance per method. PER_CLASS would be the
// alternative, but it prepares the (Spring-context-loading) instance BEFORE the
// Testcontainers extension has started the containers @DynamicPropertySource reads.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentOperationsIT {

  private static final String AUDIENCE = "it-client";
  private static final String WORKER = "it-worker";

  @Container
  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("keycloak/keycloak:26.4.1")
          .withAdminUsername("admin")
          .withAdminPassword("admin")
          .withRealmImportFile("realm/dsync-realm.json")
          .waitingFor(
              Wait.forHttp("/realms/dsync/.well-known/openid-configuration")
                  .forPort(8080)
                  .withStartupTimeout(Duration.ofMinutes(5)));

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

    // Empty string unsets config-security.yml's single jwk-set-uri (ResourceEditor
    // maps empty text to null); tokens are then trusted per issuers[].
    r.add("opentmf.security.jwk-set-uri", () -> "");
    r.add("opentmf.security.issuers[0].name", () -> "it");
    r.add("opentmf.security.issuers[0].issuer", () -> ISSUER.issuer);
    r.add("opentmf.security.issuers[0].jwk-set-uri", () -> ISSUER.jwkSetUri);
    r.add("opentmf.security.issuers[0].user-claim", () -> "email");
    r.add("opentmf.security.issuers[0].authorities-claim", () -> "roles");
    r.add("opentmf.security.issuers[0].audiences[0]", () -> AUDIENCE);
  }

  private static final String PARENT_FLOW = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                        targetNamespace="http://opentmf.org/incident-operations">
        <bpmn:process id="parentFlow" name="Parent flow" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="in" sourceRef="start" targetRef="reserve"/>
          <bpmn:callActivity id="reserve" calledElement="childFlow"/>
          <bpmn:sequenceFlow id="out" sourceRef="reserve" targetRef="end"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String CHILD_FLOW = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                        xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                        targetNamespace="http://opentmf.org/incident-operations">
        <bpmn:process id="childFlow" name="Child flow" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="in" sourceRef="start" targetRef="callWms"/>
          <bpmn:serviceTask id="callWms" name="Call WMS"
                            camunda:type="external" camunda:topic="wms"/>
          <bpmn:sequenceFlow id="out" sourceRef="callWms" targetRef="end"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

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
  private static final String ROLELESS_TOKEN =
      ISSUER.mint("nobody@it.test", List.of("nobody"));

  private static String batchId;

  @Test
  @Order(1)
  void threeFailedExternalTasksRaiseSixIncidentRowsAcrossTheTree() {
    processEngine.getRepositoryService().createDeployment()
        .name("incident-operations-fixtures")
        .addString("parentFlow.bpmn20.xml", PARENT_FLOW)
        .addString("childFlow.bpmn20.xml", CHILD_FLOW)
        .deploy();
    for (int i = 0; i < 3; i++) {
      processEngine.getRuntimeService().startProcessInstanceByKey("parentFlow");
    }

    ExternalTaskService externalTaskService = processEngine.getExternalTaskService();
    List<LockedExternalTask> tasks = externalTaskService.fetchAndLock(10, WORKER)
        .topic("wms", 60_000L).execute();
    assertThat(tasks).hasSize(3);
    for (LockedExternalTask task : tasks) {
      externalTaskService.handleFailure(task.getId(), WORKER, "WMS returned 503", 0, 0);
    }

    // 3 originating incidents in the child + 3 copies in the parents. The copies being
    // present is what makes the report's dedup below a real assertion.
    assertThat(processEngine.getRuntimeService().createIncidentQuery().count()).isEqualTo(6);
  }

  @Test
  @Order(2)
  void groupedReportCollapsesThePropagationAndNamesTheGroup() throws Exception {
    HttpResponse<String> response =
        get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow", READER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode groups = objectMapper.readTree(response.body());
    assertThat(groups).hasSize(1);
    JsonNode group = groups.get(0);
    assertThat(group.get("rootProcessDefinitionKey").asText()).isEqualTo("parentFlow");
    assertThat(group.get("processDefinitionKey").asText()).isEqualTo("childFlow");
    assertThat(group.get("processDefinitionName").asText()).isEqualTo("Child flow");
    assertThat(group.get("processDefinitionVersions").get(0).asInt()).isEqualTo(1);
    assertThat(group.get("activityId").asText()).isEqualTo("callWms");
    assertThat(group.get("activityName").asText()).isEqualTo("Call WMS");
    assertThat(group.get("activityType").asText()).isEqualTo("serviceTask");
    assertThat(group.get("incidentType").asText()).isEqualTo("failedExternalTask");
    assertThat(group.get("incidentCount").asLong()).isEqualTo(3);
    assertThat(group.get("processInstanceCount").asLong()).isEqualTo(3);
    assertThat(group.get("calledFrom").get("processDefinitionKey").asText())
        .isEqualTo("parentFlow");
    assertThat(group.get("calledFrom").get("callActivityId").asText()).isEqualTo("reserve");
    assertThat(group.get("sampleMessage").asText()).contains("WMS returned 503");
    assertThat(group.get("oldestIncident").isNull()).isFalse();
    JsonNode selector = group.get("selector");
    assertThat(selector.get("rootProcessDefinitionKey").asText()).isEqualTo("parentFlow");
    assertThat(selector.get("processDefinitionKey").asText()).isEqualTo("childFlow");
    assertThat(selector.get("activityId").asText()).isEqualTo("callWms");
    assertThat(selector.get("incidentType").asText()).isEqualTo("failedExternalTask");
    assertThat(selector.get("calledFrom").get("processDefinitionKey").asText())
        .isEqualTo("parentFlow");
    assertThat(selector.get("calledFrom").get("callActivityId").asText()).isEqualTo("reserve");
  }

  @Test
  @Order(3)
  void reportFiltersAndGuardsItsDoor() throws Exception {
    HttpResponse<String> filtered = get(
        "/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow&minIncidents=4",
        READER_TOKEN);
    assertThat(filtered.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(filtered.body())).isEmpty();

    assertThat(get("/engine-rest/extensions/incident/groups", READER_TOKEN).statusCode())
        .as("rootProcessDefinitionKey is required")
        .isEqualTo(400);
    assertThat(get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow"
        + "&minIncidents=abc", READER_TOKEN).statusCode())
        .as("a malformed minIncidents is a 400, not JAX-RS's 404")
        .isEqualTo(400);
    assertThat(get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow", null)
        .statusCode()).isEqualTo(401);
    assertThat(get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow",
        ROLELESS_TOKEN).statusCode()).isEqualTo(403);
  }

  @Test
  @Order(4)
  void tmf630ListPagesSortsAndSelectsFields() throws Exception {
    // Page 1 of 2: TMF-630 semantics - 206, range headers, a next link.
    HttpResponse<String> firstPage = get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow"
            + "&limit=2&sort=-incidentTimestamp", READER_TOKEN);
    assertThat(firstPage.statusCode()).isEqualTo(206);
    assertThat(firstPage.headers().firstValue("X-Total-Count")).contains("3");
    assertThat(firstPage.headers().firstValue("Content-Range")).contains("items 1-2/3");
    assertThat(firstPage.headers().firstValue("Link").orElseThrow())
        .contains("offset=2")
        .contains("rel=\"next\"");
    JsonNode rows = objectMapper.readTree(firstPage.body());
    assertThat(rows).hasSize(2);
    JsonNode row = rows.get(0);
    assertThat(row.get("originating").asBoolean()).isTrue();
    assertThat(row.get("processDefinitionKey").asText()).isEqualTo("childFlow");
    assertThat(row.get("processDefinitionName").asText()).isEqualTo("Child flow");
    assertThat(row.get("activityName").asText()).isEqualTo("Call WMS");
    assertThat(row.get("incidentType").asText()).isEqualTo("failedExternalTask");
    assertThat(row.get("href").asText())
        .isEqualTo("/cadenzaflow/v1/engine-rest/incident/" + row.get("id").asText());

    // Last page: still 206 - the toolkit reserves 200 for "the whole result fits
    // from offset 0" (the plan's step-4 draft expected 200 here; the toolkit's
    // actual estate-wide semantics win).
    HttpResponse<String> lastPage = get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow&limit=2&offset=2",
        READER_TOKEN);
    assertThat(lastPage.statusCode()).isEqualTo(206);
    assertThat(lastPage.headers().firstValue("Content-Range")).contains("items 3-3/3");
    assertThat(lastPage.headers().firstValue("Link").orElseThrow())
        .doesNotContain("rel=\"next\"");
    assertThat(objectMapper.readTree(lastPage.body())).hasSize(1);

    // Out-of-range offset, unknown sort field, field selection.
    assertThat(get("/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow&offset=10",
        READER_TOKEN).statusCode()).isEqualTo(416);
    assertThat(get("/engine-rest/extensions/incident?sort=bogus", READER_TOKEN).statusCode())
        .isEqualTo(400);

    HttpResponse<String> selected = get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow"
            + "&fields=id,activityName", READER_TOKEN);
    assertThat(selected.statusCode()).isEqualTo(200);
    JsonNode selectedRow = objectMapper.readTree(selected.body()).get(0);
    assertThat(selectedRow.has("id")).isTrue();
    assertThat(selectedRow.get("activityName").asText()).isEqualTo("Call WMS");
    assertThat(selectedRow.has("incidentMessage")).isFalse();

    // Unlike the grouped report's counts, the list hides nothing: querying the
    // parent definition surfaces the propagated copies, flagged as non-originating.
    HttpResponse<String> parentRows = get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=parentFlow", READER_TOKEN);
    JsonNode copies = objectMapper.readTree(parentRows.body());
    assertThat(copies).hasSize(3);
    assertThat(copies.get(0).get("originating").asBoolean()).isFalse();

    // Same inherited RBAC as the rest of /engine-rest.
    assertThat(get("/engine-rest/extensions/incident", null).statusCode()).isEqualTo(401);
    assertThat(get("/engine-rest/extensions/incident", ROLELESS_TOKEN).statusCode())
        .isEqualTo(403);
  }

  @Test
  @Order(5)
  void timeRangeIsHalfOpenOnBothEndpoints() throws Exception {
    // The group's own oldest/newest timestamps (engine date format, +0000 zone) are
    // fed back in verbatim - the endpoints must accept their own output.
    JsonNode group = objectMapper.readTree(
        get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow",
            READER_TOKEN).body()).get(0);
    String newest = URLEncoder.encode(group.get("newestIncident").asText(),
        StandardCharsets.UTF_8);
    String oldest = URLEncoder.encode(group.get("oldestIncident").asText(),
        StandardCharsets.UTF_8);

    // after is INCLUSIVE: the boundary incident itself stays in.
    JsonNode fromNewest = objectMapper.readTree(get(
        "/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow"
            + "&incidentTimestampAfter=" + newest, READER_TOKEN).body());
    assertThat(fromNewest).hasSize(1);
    assertThat(fromNewest.get(0).get("incidentCount").asLong()).isPositive();
    // A windowed report echoes its window into every selector, verbatim - so a
    // retry posted from this filtered view touches only the displayed slice.
    assertThat(fromNewest.get(0).get("selector").get("incidentTimestampAfter").asText())
        .isEqualTo(group.get("newestIncident").asText());
    assertThat(fromNewest.get(0).get("selector").get("incidentTimestampBefore").isNull())
        .isTrue();

    // before is EXCLUSIVE: nothing was raised strictly before the oldest incident,
    // and the oldest itself must NOT match its own timestamp as an upper bound.
    assertThat(objectMapper.readTree(get(
        "/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow"
            + "&incidentTimestampBefore=" + oldest, READER_TOKEN).body())).isEmpty();

    // Same half-open contract on the list (the stock engine filter is strict-after;
    // the service bridges with a millisecond shift).
    HttpResponse<String> listFromNewest = get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow"
            + "&incidentTimestampAfter=" + newest, READER_TOKEN);
    assertThat(objectMapper.readTree(listFromNewest.body()).size()).isPositive();
    assertThat(objectMapper.readTree(get(
        "/engine-rest/extensions/incident?processDefinitionKeyIn=childFlow"
            + "&incidentTimestampBefore=" + oldest, READER_TOKEN).body())).isEmpty();

    // Unparseable values are engine-style 400s.
    assertThat(get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow"
        + "&incidentTimestampAfter=yesterday", READER_TOKEN).statusCode()).isEqualTo(400);
  }

  @Test
  @Order(6)
  void windowedRetryStaysInsideItsWindow() throws Exception {
    // before = the oldest incident's own timestamp is EXCLUSIVE, so this window is
    // provably empty - the retry must match nothing and resolve nothing, leaving all
    // six incident rows for the full retry that follows.
    JsonNode group = objectMapper.readTree(
        get("/engine-rest/extensions/incident/groups?rootProcessDefinitionKey=parentFlow",
            READER_TOKEN).body()).get(0);
    String body = """
        {
          "rootProcessDefinitionKey": "parentFlow",
          "processDefinitionKey": "childFlow",
          "activityId": "callWms",
          "incidentType": "failedExternalTask",
          "incidentTimestampBefore": "%s",
          "retries": 1
        }
        """.formatted(group.get("oldestIncident").asText());

    HttpResponse<String> response =
        post("/engine-rest/extensions/incident/retry", body, WRITER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode result = objectMapper.readTree(response.body());
    assertThat(result.get("incidentCount").asLong()).isZero();
    assertThat(result.get("batches")).isEmpty();
    assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
        .as("a windowed retry must not touch incidents outside its window")
        .isEqualTo(6);

    // The caller is part of the group key: the same activity reached through another
    // call activity is a different group, so a selector naming one matches only that one.
    HttpResponse<String> otherCaller = post("/engine-rest/extensions/incident/retry", """
        {
          "rootProcessDefinitionKey": "parentFlow",
          "processDefinitionKey": "childFlow",
          "activityId": "callWms",
          "incidentType": "failedExternalTask",
          "calledFrom": { "processDefinitionKey": "parentFlow", "callActivityId": "otherReserve" },
          "retries": 1
        }
        """, WRITER_TOKEN);
    assertThat(otherCaller.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(otherCaller.body()).get("incidentCount").asLong()).isZero();
    assertThat(processEngine.getRuntimeService().createIncidentQuery().count()).isEqualTo(6);

    // An unparseable window is refused before anything happens.
    assertThat(post("/engine-rest/extensions/incident/retry", """
        {
          "rootProcessDefinitionKey": "parentFlow",
          "processDefinitionKey": "childFlow",
          "activityId": "callWms",
          "incidentType": "failedExternalTask",
          "incidentTimestampAfter": "yesterday",
          "retries": 1
        }
        """, WRITER_TOKEN).statusCode()).isEqualTo(400);
  }

  @Test
  @Order(7)
  void groupRetryResolvesTheWholeGroupThroughEngineBatches() throws Exception {
    HttpResponse<String> response = post("/engine-rest/extensions/incident/retry",
        retryBody(1), WRITER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode result = objectMapper.readTree(response.body());
    assertThat(result.get("incidentCount").asLong()).isEqualTo(3);
    assertThat(result.get("batches")).hasSize(1);
    JsonNode batch = result.get("batches").get(0);
    assertThat(batch.get("type").asText()).isEqualTo("set-external-task-retries");
    assertThat(batch.get("size").asInt()).isEqualTo(3);
    batchId = batch.get("id").asText();

    // The job executor is on in the it profile, so the batch runs by itself: retries
    // above zero also resolve the incidents and their parent copies inside the engine.
    Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
        .until(() -> processEngine.getRuntimeService().createIncidentQuery().count() == 0);

    List<ExternalTask> externalTasks =
        processEngine.getExternalTaskService().createExternalTaskQuery().list();
    assertThat(externalTasks)
        .hasSize(3)
        .allSatisfy(task -> assertThat(task.getRetries()).isEqualTo(1));

    Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
        .until(() -> get("/engine-rest/history/batch/" + batchId, READER_TOKEN)
            .statusCode() == 200);
  }

  @Test
  @Order(8)
  void secondRetryOfTheSameGroupIsAnEmptyOutcomeNotAnError() throws Exception {
    HttpResponse<String> response = post("/engine-rest/extensions/incident/retry",
        retryBody(1), WRITER_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode result = objectMapper.readTree(response.body());
    assertThat(result.get("incidentCount").asLong()).isZero();
    assertThat(result.get("batches")).isEmpty();
  }

  @Test
  @Order(9)
  void retryGuardsItsDoor() throws Exception {
    assertThat(post("/engine-rest/extensions/incident/retry", retryBody(0), WRITER_TOKEN)
        .statusCode()).as("retries below 1 are refused").isEqualTo(400);
    assertThat(post("/engine-rest/extensions/incident/retry", retryBody(1), READER_TOKEN)
        .statusCode()).as("reader may look but not retry").isEqualTo(403);
    assertThat(post("/engine-rest/extensions/incident/retry", retryBody(1), null)
        .statusCode()).isEqualTo(401);
  }

  private static String retryBody(int retries) {
    return """
        {
          "rootProcessDefinitionKey": "parentFlow",
          "processDefinitionKey": "childFlow",
          "activityId": "callWms",
          "incidentType": "failedExternalTask",
          "calledFrom": { "processDefinitionKey": "parentFlow", "callActivityId": "reserve" },
          "retries": %d
        }
        """.formatted(retries);
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
    var builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }
}
