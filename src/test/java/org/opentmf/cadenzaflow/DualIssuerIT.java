package org.opentmf.cadenzaflow;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Mixed-provider scenario: the webapp authenticates ONLY against Entra ID, while
 * engine-rest accepts tokens from BOTH Entra ID and Keycloak via
 * openid-rbac-security 2.3.0 multi-issuer support ({@code opentmf.security.issuers[]}).
 *
 * <p>Both issuers are simulated in-test: RSA keys are generated, their JWK sets are
 * served as {@code file:} resources, and tokens are minted per test. Entra's Graph and
 * token endpoints run on an embedded WireMock so the Entra identity plugin boots for
 * the webapp side.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class DualIssuerIT {

  /**
   * Entra v2 access tokens carry the BARE CLIENT ID as audience (an app registration
   * left on token version 1 would carry the App ID URI {@code api://<client-id>}
   * instead, with issuer {@code https://sts.windows.net/<tenant>/}). Both values must
   * match the real token: {@code issuers[]} routes by exact issuer and validates the
   * audience, so a v1/v2 mix-up is a silent 401.
   */
  private static final String ENTRA_AUDIENCE = "test-client";
  private static final String KC_AUDIENCE = "camunda-identity-service";

  /** Minimal stand-in for an OIDC provider: RSA key + file-served JWKS + token minting. */
  static final class TestIssuer {
    final String issuer;
    final RSAKey key;
    final String jwkSetUri;

    TestIssuer(String issuer) {
      try {
        this.issuer = issuer;
        this.key = new RSAKeyGenerator(2048)
            .keyID(Integer.toHexString(issuer.hashCode())).generate();
        Path jwks = Files.createTempFile("jwks", ".json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
        this.jwkSetUri = jwks.toUri().toString();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    String mint(Consumer<JWTClaimsSet.Builder> customizer) {
      return mintWithIssuer(issuer, customizer);
    }

    String mintWithIssuer(String iss, Consumer<JWTClaimsSet.Builder> customizer) {
      try {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(iss)
            .subject("subject-id")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        customizer.accept(claims);
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  static final TestIssuer ENTRA =
      new TestIssuer("https://login.microsoftonline.com/test-tenant/v2.0");
  static final TestIssuer KEYCLOAK =
      new TestIssuer("http://keycloak.test/realms/dsync");

  // Entra Graph + token endpoint for the identity plugin (webapp side)
  private static final WireMockServer GRAPH = new WireMockServer(0);

  static {
    GRAPH.start();
    GRAPH.stubFor(post(urlPathEqualTo("/test-tenant/oauth2/v2.0/token")).willReturn(okJson(
        "{\"access_token\":\"graph-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    GRAPH.stubFor(WireMock.get(urlPathEqualTo("/v1.0/groups"))
        .withQueryParam("$filter",
            equalTo("displayName eq 'camunda-admin' and securityEnabled eq true"))
        .willReturn(okJson("{\"value\":[{\"id\":\"g-admin\",\"displayName\":\"camunda-admin\","
            + "\"securityEnabled\":true}]}")));
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    // webapp identity: Entra plugin against the WireMock Graph
    r.add("plugin.identity.provider", () -> "entra");
    r.add("plugin.identity.entra.tenant-id", () -> "test-tenant");
    r.add("plugin.identity.entra.client-id", () -> "test-client");
    r.add("plugin.identity.entra.client-secret", () -> "test-secret");
    r.add("plugin.identity.entra.authority-host", GRAPH::baseUrl);
    r.add("plugin.identity.entra.graph-base-url", () -> GRAPH.baseUrl() + "/v1.0");
    r.add("plugin.identity.entra.administrator-group-name", () -> "camunda-admin");

    // webapp SSO: ONLY Entra (repoint the registration's provider endpoints)
    r.add("spring.security.oauth2.client.registration.oidc.redirect-uri",
        () -> "{baseUrl}/login/oauth2/code/azure");
    r.add("spring.security.oauth2.client.provider.oidc.authorization-uri",
        () -> GRAPH.baseUrl() + "/test-tenant/oauth2/v2.0/authorize");
    r.add("spring.security.oauth2.client.provider.oidc.token-uri",
        () -> GRAPH.baseUrl() + "/test-tenant/oauth2/v2.0/token");
    r.add("spring.security.oauth2.client.provider.oidc.jwk-set-uri", () -> ENTRA.jwkSetUri);
    r.add("spring.security.oauth2.client.provider.oidc.user-name-attribute", () -> "email");

    // REST security: BOTH issuers; empty string unsets config-security.yml's single
    // jwk-set-uri (ResourceEditor maps empty text to null), else 2.3.0's
    // exactly-one-trust-mode validation would fail
    r.add("opentmf.security.jwk-set-uri", () -> "");
    r.add("opentmf.security.issuers[0].name", () -> "entra");
    r.add("opentmf.security.issuers[0].issuer", () -> ENTRA.issuer);
    r.add("opentmf.security.issuers[0].jwk-set-uri", () -> ENTRA.jwkSetUri);
    r.add("opentmf.security.issuers[0].user-claim", () -> "email");
    r.add("opentmf.security.issuers[0].authorities-claim", () -> "roles");
    r.add("opentmf.security.issuers[0].audiences[0]", () -> ENTRA_AUDIENCE);
    r.add("opentmf.security.issuers[1].name", () -> "keycloak");
    r.add("opentmf.security.issuers[1].issuer", () -> KEYCLOAK.issuer);
    r.add("opentmf.security.issuers[1].jwk-set-uri", () -> KEYCLOAK.jwkSetUri);
    r.add("opentmf.security.issuers[1].user-claim", () -> "email");
    r.add("opentmf.security.issuers[1].authorities-claim", () -> "roles");
    r.add("opentmf.security.issuers[1].audiences[0]", () -> KC_AUDIENCE);
  }

  @AfterAll
  static void stopWireMock() {
    GRAPH.stop();
  }

  @LocalServerPort
  private int port;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path)).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void entraTokenIsAcceptedOnEngineRest() throws Exception {
    String token = ENTRA.mint(c -> c.claim("email", "user@entra.test")
        .claim("roles", List.of("ebu-federation-admin")).audience(ENTRA_AUDIENCE));
    HttpResponse<String> response = get("/engine-rest/engine", token);
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("default"));
  }

  @Test
  void keycloakTokenIsAcceptedOnTheSameEndpoint() throws Exception {
    String token = KEYCLOAK.mint(c -> c.claim("email", "service@keycloak.test")
        .claim("roles", List.of("ebu-federation-admin")).audience(KC_AUDIENCE));
    Assertions.assertEquals(200, get("/engine-rest/engine", token).statusCode());
  }

  @Test
  void unknownIssuerIsRejectedWithoutFallback() throws Exception {
    String token = ENTRA.mintWithIssuer("https://attacker.example/v2.0",
        c -> c.claim("email", "x@y").claim("roles", List.of("admin"))
            .audience(ENTRA_AUDIENCE));
    Assertions.assertEquals(401, get("/engine-rest/engine", token).statusCode());
  }

  @Test
  void wrongAudienceIsRejectedPerIssuer() throws Exception {
    String token = ENTRA.mint(c -> c.claim("email", "user@entra.test")
        .claim("roles", List.of("admin")).audience("some-other-app"));
    Assertions.assertEquals(401, get("/engine-rest/engine", token).statusCode());
  }

  @Test
  void missingRoleIsForbiddenForBothIssuers() throws Exception {
    String entra = ENTRA.mint(c -> c.claim("email", "user@entra.test")
        .claim("roles", List.of("nobody")).audience(ENTRA_AUDIENCE));
    String keycloak = KEYCLOAK.mint(c -> c.claim("email", "service@keycloak.test")
        .claim("roles", List.of("nobody")).audience(KC_AUDIENCE));
    Assertions.assertEquals(403, get("/engine-rest/task", entra).statusCode());
    Assertions.assertEquals(403, get("/engine-rest/task", keycloak).statusCode());
  }

  @Test
  void anonymousAndGarbageTokensAreRejected() throws Exception {
    Assertions.assertEquals(401, get("/engine-rest/task", null).statusCode());
    Assertions.assertEquals(401, get("/engine-rest/task", "not-a-jwt").statusCode());
  }

  @Test
  void webappLoginGoesOnlyToEntra() throws Exception {
    // /app/** -> oauth2 authorization endpoint -> Entra authorize URL (nothing Keycloak)
    HttpResponse<String> first = get("/app/cockpit", null);
    Assertions.assertEquals(302, first.statusCode());
    String authPath = first.headers().firstValue("Location").orElseThrow();
    var request = HttpRequest.newBuilder().uri(URI.create(authPath)).GET().build();
    HttpResponse<String> second = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(302, second.statusCode());
    String entraAuthorize = second.headers().firstValue("Location").orElseThrow();
    Assertions.assertTrue(
        entraAuthorize.startsWith(GRAPH.baseUrl() + "/test-tenant/oauth2/v2.0/authorize"),
        "expected redirect to the Entra authorize endpoint, got: " + entraAuthorize);
  }
}
