package org.opentmf.cadenzaflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
 * CF-SHARED-CALLBACK-3: Cockpit signs in through the shared page by token. The page (played
 * by the test) posts an access token to {@code POST /app/login-with-token}; the token is
 * validated as {@code /engine-rest} validates bearers (here: an
 * {@code opentmf.security.issuers[]} entry with an audience, the shape of
 * {@link IncidentOperationsIT}), and a valid one opens a fresh webapp session.
 *
 * <p>Keycloak stays the webapp registration's provider, so the sign-out's end-session URL
 * is derived from it as in production.</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class LoginWithTokenIT {

  private static final String AUDIENCE = "it-client";
  // The webapp's OIDC client registration (application-it.yml): the aud of its ID tokens.
  private static final String CLIENT_ID = "camunda-identity-service";
  // A user the IT's Keycloak directory knows (dsync realm import): the webapp looks the
  // signed-in user up in the identity provider and cannot authenticate an unknown one.
  private static final String EMAIL = "camunda@dnext-technology.com";
  private static final String SHARED_CALLBACK = "https://ui.example.test/auth/callback";
  private static final String LOGIN_PAGE = "https://ui.example.test/auth/cockpit";

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

    String mint(String audience, Instant expiresAt) {
      return mint(audience, expiresAt, Map.of("email", EMAIL, "preferred_username", "cockpit-user"));
    }

    String mint(String audience, Instant expiresAt, Map<String, Object> userClaims) {
      return mint(List.of(audience), expiresAt, userClaims);
    }

    /** An ID token: issued to the OIDC client, so {@code aud} names the client id(s). */
    String idToken(String... audience) {
      return mint(List.of(audience), Instant.now().plusSeconds(300),
          Map.of("email", EMAIL, "preferred_username", "cockpit-user", "nonce", "n-0S6_WzA2Mj"));
    }

    String mint(List<String> audience, Instant expiresAt, Map<String, Object> userClaims) {
      try {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("subject-id")
            .audience(audience)
            .issueTime(Date.from(expiresAt.minusSeconds(600)))
            .expirationTime(Date.from(expiresAt))
            .claim("oid", "00000000-0000-0000-0000-0000000000a1")
            .claim("roles", List.of("ebu-federation-admin"));
        userClaims.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    String valid() {
      return mint(AUDIENCE, Instant.now().plusSeconds(300));
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

    // Empty string unsets config-security.yml's single jwk-set-uri; tokens are then
    // trusted per issuers[] - with an audience, so the other-audience cell means something.
    r.add("opentmf.security.jwk-set-uri", () -> "");
    r.add("opentmf.security.issuers[0].name", () -> "it");
    r.add("opentmf.security.issuers[0].issuer", () -> ISSUER.issuer);
    r.add("opentmf.security.issuers[0].jwk-set-uri", () -> ISSUER.jwkSetUri);
    // A directory-provider project's shape (OPENTMF-CADENZAFLOW-BEARER-LOGIN-USER-CLAIM-1):
    // /engine-rest maps tokens by the stable `oid`, while the directory keys users on their
    // mail address - so the Cockpit login names its user by `email` (bearer-login.user-claim).
    r.add("opentmf.security.issuers[0].user-claim", () -> "oid");
    r.add("opentmf.security.issuers[0].authorities-claim", () -> "roles");
    r.add("opentmf.security.issuers[0].audiences[0]", () -> AUDIENCE);

    r.add("cadenzaflow.sso.shared-callback-url", () -> SHARED_CALLBACK);
    r.add("cadenzaflow.sso.login-page-url", () -> LOGIN_PAGE);
    r.add("cadenzaflow.sso.bearer-login.user-claim", () -> "email");
  }

  @LocalServerPort
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void anUnauthenticatedWebappRequestGoesToTheLoginPage() throws Exception {
    HttpResponse<String> response = get("/app/cockpit/", null);

    assertEquals(302, response.statusCode());
    assertEquals(LOGIN_PAGE + "?returnTo=%2Fcadenzaflow%2Fv1%2Fapp%2Fcockpit%2F",
        location(response));
  }

  @Test
  void aValidTokenOpensAFreshCockpitSession() throws Exception {
    // The browser already holds a session (the saved request of its first visit).
    String before = sessionCookie(get("/app/cockpit/", null)).orElseThrow();

    HttpResponse<String> login = login(ISSUER.valid(), before, null);

    assertEquals(204, login.statusCode(), login.body());
    assertEquals("/cadenzaflow/v1/app/cockpit/", location(login));
    String session = sessionCookie(login).orElseThrow();
    assertNotEquals(before, session, "the session id must change at login");

    // Cockpit's own first page opens with the session. It is also where the webapp's
    // container filter attaches the engine user: that filter acts only on engine-scoped
    // paths (/app/<app>/<engine>/, /api/engine/engine/<engine>/...), which
    // /api/admin/auth/user/<engine> is not. The browser always loads this page first.
    HttpResponse<String> cockpit = get("/app/cockpit/default/", session);
    assertEquals(200, cockpit.statusCode(), cockpit.headers().map().toString());

    // The engine webapp sees the token's user as the signed-in user: its EMAIL (the
    // bearer-login.user-claim), not the oid /engine-rest maps it by.
    HttpResponse<String> me = get("/api/admin/auth/user/default", session);
    assertEquals(200, me.statusCode(), me.body());
    assertTrue(me.body().contains(EMAIL), me.body());

    // The pre-login session id was not upgraded.
    assertEquals(302, get("/api/admin/auth/user/default", before).statusCode());
  }

  /**
   * OPENTMF-CADENZAFLOW-UNKNOWN-DIRECTORY-USER-401-1: a signed-in user the identity provider
   * does not know. The engine webapp's own filter would NPE on its null lookup (500); the
   * wrapper's provider answers unsuccessful first, so the webapp says 401.
   */
  @Test
  void aUserUnknownToTheDirectoryGets401NotA500() throws Exception {
    String stranger = ISSUER.mint(AUDIENCE, Instant.now().plusSeconds(300),
        Map.of("email", "stranger@example.test", "preferred_username", "stranger"));
    String session = sessionCookie(login(stranger, null, null)).orElseThrow();

    HttpResponse<String> cockpit = get("/app/cockpit/default/", session);

    assertEquals(401, cockpit.statusCode(), cockpit.body());
  }

  @Test
  void anExpiredTokenIsRefusedAndOpensNothing() throws Exception {
    // Past Spring's 60 s clock-skew allowance.
    HttpResponse<String> login =
        login(ISSUER.mint(AUDIENCE, Instant.now().minusSeconds(300)), null, null);

    assertEquals(401, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  @Test
  void aTokenForAnotherAudienceIsRefusedAndOpensNothing() throws Exception {
    HttpResponse<String> login =
        login(ISSUER.mint("some-other-api", Instant.now().plusSeconds(300)), null, null);

    assertEquals(401, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  /**
   * D-457: /engine-rest accepts a client-credentials token, the bearer login does not. The
   * Keycloak shape: a service account's preferred_username, user claim present.
   */
  @Test
  void aKeycloakServiceAccountTokenIsRefusedAndOpensNothing() throws Exception {
    String serviceAccount = ISSUER.mint(AUDIENCE, Instant.now().plusSeconds(300),
        Map.of("email", "worker@example.test", "preferred_username", "service-account-worker"));
    // The same token is good enough for the engine REST API...
    HttpResponse<String> rest = send(HttpRequest.newBuilder(app("/engine-rest/version"))
        .header("Authorization", "Bearer " + serviceAccount).GET());
    assertNotEquals(401, rest.statusCode(), rest.body());

    // ...but opens no webapp session.
    HttpResponse<String> login = login(serviceAccount, null, null);
    assertEquals(401, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  @Test
  void aTokenWithoutTheUserClaimIsRefusedAndOpensNothing() throws Exception {
    HttpResponse<String> login = login(ISSUER.mint(AUDIENCE, Instant.now().plusSeconds(300),
        Map.of("preferred_username", "someone")), null, null);

    assertEquals(401, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  @Test
  void aCrossSiteCallIsRefusedAndOpensNothing() throws Exception {
    HttpResponse<String> login = send(HttpRequest.newBuilder(app("/app/login-with-token"))
        .header("Authorization", "Bearer " + ISSUER.valid())
        .header("Sec-Fetch-Site", "cross-site")
        .POST(HttpRequest.BodyPublishers.noBody()));

    assertEquals(403, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  /**
   * CF-ID-TOKEN-AUDIENCE-CLIENT-ID-1 (V209): an Entra ID token's {@code aud} is the console's
   * client id alone, never the API audience. It must open the session, and it must still be
   * refused as a bearer token by the REST API.
   */
  @Test
  void anEntraShapedIdTokenSignsInAndIsStillRefusedByTheRestApi() throws Exception {
    String idToken = ISSUER.idToken(CLIENT_ID);

    HttpResponse<String> login = login(ISSUER.valid(), null, idToken);

    assertEquals(204, login.statusCode(), login.body());
    assertTrue(sessionCookie(login).isPresent());
    HttpResponse<String> rest = send(HttpRequest.newBuilder(app("/engine-rest/version"))
        .header("Authorization", "Bearer " + idToken).GET());
    assertEquals(401, rest.statusCode(), rest.body());
  }

  /** The Keycloak shape: the ID token names the client id beside the API audience. */
  @Test
  void aKeycloakShapedIdTokenCarryingBothAudiencesSignsIn() throws Exception {
    HttpResponse<String> login =
        login(ISSUER.valid(), null, ISSUER.idToken(AUDIENCE, CLIENT_ID));

    assertEquals(204, login.statusCode(), login.body());
    assertTrue(sessionCookie(login).isPresent());
  }

  @Test
  void anIdTokenForAForeignAudienceRefusesTheLoginAndOpensNothing() throws Exception {
    HttpResponse<String> login =
        login(ISSUER.valid(), null, ISSUER.idToken("some-other-client"));

    assertEquals(401, login.statusCode());
    assertEquals(Optional.empty(), sessionCookie(login));
  }

  // A login request carrying ~20 KB of headers (the ALB's OIDC session cookies) is NOT a cell
  // here: the image keeps server.max-http-request-header-size at 8KB, so whether it passes is
  // the deployment's setting (the chart default, a dnms-deploy row), not this application's.

  @Test
  void signOutReturnsToTheSharedUrlWithThePagesIdTokenAsHint() throws Exception {
    String idToken = ISSUER.idToken(CLIENT_ID);
    String session = sessionCookie(login(ISSUER.valid(), null, idToken)).orElseThrow();

    HttpResponse<String> logout = get("/logout", session);

    assertEquals(302, logout.statusCode());
    String target = location(logout);
    assertTrue(target.contains("/realms/dsync/protocol/openid-connect/logout?"), target);
    assertTrue(target.contains("id_token_hint=" + idToken), target);
    assertTrue(target.contains("post_logout_redirect_uri=" + SHARED_CALLBACK), target);
    assertTrue(target.endsWith("&state=cf.so:/cadenzaflow/v1/app/"), target);
    assertEquals(302, get("/api/admin/auth/user/default", session).statusCode());
  }

  private HttpResponse<String> login(String accessToken, String sessionCookie, String idToken)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(app("/app/login-with-token"))
        .header("Authorization", "Bearer " + accessToken)
        .header("Origin", "http://localhost:" + port)
        .header("Sec-Fetch-Site", "same-origin")
        .POST(HttpRequest.BodyPublishers.noBody());
    if (sessionCookie != null) {
      request.header("Cookie", "JSESSIONID=" + sessionCookie);
    }
    if (idToken != null) {
      request.header("X-ID-Token", idToken);
    }
    return send(request);
  }

  private HttpResponse<String> get(String path, String sessionCookie) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(app(path)).GET();
    if (sessionCookie != null) {
      request.header("Cookie", "JSESSIONID=" + sessionCookie);
    }
    return send(request);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI app(String path) {
    return URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path);
  }

  private static String location(HttpResponse<?> response) {
    String location = response.headers().firstValue("Location").orElse(null);
    assertNotNull(location, "no Location on " + response.statusCode());
    return location;
  }

  private static Optional<String> sessionCookie(HttpResponse<?> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .map(c -> c.split(";", 2)[0])
        .filter(c -> c.startsWith("JSESSIONID="))
        .map(c -> c.substring("JSESSIONID=".length()))
        .filter(v -> !v.isEmpty())
        .reduce((first, last) -> last);
  }
}
