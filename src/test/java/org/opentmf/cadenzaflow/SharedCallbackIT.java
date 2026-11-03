package org.opentmf.cadenzaflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CF-SHARED-CALLBACK-1: with {@code cadenzaflow.sso.shared-callback-url} set, Cockpit's
 * sign-in leaves through the shared browser callback and still completes on this
 * application's own {@code /login/oauth2/code/oidc}.
 *
 * <p>A real round trip against Keycloak: the authorize redirect carries the shared
 * {@code redirect_uri}, a {@code cf.} state and an S256 challenge; a user signs in on
 * Keycloak's form; Keycloak sends the code to the shared URL (not served here - the test
 * plays the shared page and forwards {@code code} and {@code state} unchanged); the code
 * redeemed with the same {@code redirect_uri} and the PKCE verifier signs the user in.
 * The template case (property empty) is pinned in {@link OpenTmfCadenzaFlowApplicationIT}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class SharedCallbackIT {

  private static final String SHARED_CALLBACK = "https://ui.example.test/auth/callback";
  private static final String CLIENT_ID = "camunda-identity-service";
  private static final String USER = "shared-callback-user";
  private static final String PASSWORD = "shared-callback-password";
  private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*\\saction=\"([^\"]+)\"");

  @Container
  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("keycloak/keycloak:26.4.1")
          .withAdminUsername("admin")
          .withAdminPassword("admin")
          .withRealmImportFile("realm/dsync-realm.json")
          // Same wait as OpenTmfCadenzaFlowApplicationIT: the HTTP port, and time enough
          // for the realm import on a slow machine.
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
    r.add("plugin.identity.keycloak.keycloak-issuer-url", () -> issuer);
    r.add("plugin.identity.keycloak.keycloak-admin-url", () -> admin);
    r.add("cadenzaflow.sso.shared-callback-url", () -> SHARED_CALLBACK);
  }

  /**
   * The realm lists the shared URL as a valid redirect of the webapp client (the Entra
   * registration's one redirect URI), and a user with a known password exists to sign in.
   * The imported users carry hashed passwords only.
   */
  @BeforeAll
  static void prepareRealm() {
    RealmResource realm = KEYCLOAK.getKeycloakAdminClient().realm("dsync");

    ClientRepresentation client = realm.clients().findByClientId(CLIENT_ID).get(0);
    List<String> redirectUris = new ArrayList<>(client.getRedirectUris());
    redirectUris.add(SHARED_CALLBACK);
    client.setRedirectUris(redirectUris);
    realm.clients().get(client.getId()).update(client);

    CredentialRepresentation password = new CredentialRepresentation();
    password.setType(CredentialRepresentation.PASSWORD);
    password.setValue(PASSWORD);
    password.setTemporary(false);
    UserRepresentation user = new UserRepresentation();
    user.setUsername(USER);
    user.setEmail(USER + "@example.test");
    user.setEmailVerified(true);
    // Keycloak 26's user profile requires both names; without them the sign-in stops on
    // an "update profile" form instead of redirecting with a code.
    user.setFirstName("Shared");
    user.setLastName("Callback");
    user.setEnabled(true);
    user.setCredentials(List.of(password));
    user.setGroups(List.of("/camunda-admin"));
    try (Response created = realm.users().create(user)) {
      assertEquals(201, created.getStatus(), "creating the sign-in user");
    }
  }

  @LocalServerPort
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final Map<String, String> appCookies = new LinkedHashMap<>();
  private final Map<String, String> keycloakCookies = new LinkedHashMap<>();

  @Test
  void authorizeRedirectCarriesTheSharedCallbackACfStateAndPkce() throws Exception {
    Map<String, String> authorize = query(authorizeRedirect());

    assertEquals(SHARED_CALLBACK, authorize.get("redirect_uri"));
    assertTrue(authorize.get("state").startsWith("cf."), authorize.get("state"));
    assertEquals("S256", authorize.get("code_challenge_method"));
    assertNotNull(authorize.get("code_challenge"));
  }

  @Test
  void theCodeBouncedFromTheSharedPageSignsTheUserIn() throws Exception {
    URI authorize = authorizeRedirect();

    // Keycloak's sign-in form, then the credentials posted to its action.
    HttpResponse<String> form = send(HttpRequest.newBuilder(authorize).GET(), keycloakCookies);
    assertEquals(200, form.statusCode(), form.body());
    Matcher action = FORM_ACTION.matcher(form.body());
    assertTrue(action.find(), "no sign-in form in Keycloak's page");
    String formAction = action.group(1).replace("&amp;", "&");
    HttpResponse<String> signedIn = send(
        HttpRequest.newBuilder(URI.create(formAction))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "username=" + encode(USER) + "&password=" + encode(PASSWORD)
                    + "&credentialId=")),
        keycloakCookies);
    assertEquals(302, signedIn.statusCode(), signedIn.body());

    // Keycloak answers the SHARED page, with the code and the cf. state.
    URI toSharedPage = URI.create(location(signedIn));
    assertTrue(toSharedPage.toString().startsWith(SHARED_CALLBACK + "?"), toSharedPage.toString());
    Map<String, String> bounce = query(toSharedPage);
    assertNotNull(bounce.get("code"));
    assertTrue(bounce.get("state").startsWith("cf."), bounce.get("state"));

    // The shared page bounces the browser to Cockpit's login path, query unchanged.
    HttpResponse<String> callback = send(
        HttpRequest.newBuilder(
            app("/login/oauth2/code/oidc?" + toSharedPage.getRawQuery())).GET(),
        appCookies);
    assertEquals(302, callback.statusCode(), callback.body());
    String afterSignIn = location(callback);
    assertFalse(afterSignIn.contains("error"), afterSignIn);
    assertTrue(afterSignIn.contains("/app/cockpit"), afterSignIn);

    // Signed in: the saved page is served (after the webapp's own redirects, if any)
    // without another trip to the provider.
    URI next = resolveAgainstApp(afterSignIn);
    HttpResponse<String> page = send(HttpRequest.newBuilder(next).GET(), appCookies);
    for (int hop = 0; page.statusCode() == 302 && hop < 3; hop++) {
      next = resolveAgainstApp(location(page));
      assertFalse(next.getPath().contains("/oauth2/authorization"), "sent to sign in again");
      page = send(HttpRequest.newBuilder(next).GET(), appCookies);
    }
    assertEquals(200, page.statusCode(), next + " " + page.headers().map());
  }

  /**
   * An unauthenticated Cockpit request, followed through this application's redirects
   * until one leaves for Keycloak's authorize endpoint.
   */
  private URI authorizeRedirect() throws Exception {
    URI next = app("/app/cockpit/");
    for (int hop = 0; hop < 4; hop++) {
      HttpResponse<String> response = send(HttpRequest.newBuilder(next).GET(), appCookies);
      assertEquals(302, response.statusCode(), next.toString());
      next = resolveAgainstApp(location(response));
      if (next.getPort() != port) {
        assertTrue(next.getPath().endsWith("/protocol/openid-connect/auth"), next.toString());
        return next;
      }
    }
    throw new AssertionError("never redirected to the provider; last hop " + next);
  }

  private URI app(String path) {
    return URI.create("http://localhost:" + port + "/cadenzaflow/v1" + path);
  }

  private URI resolveAgainstApp(String location) {
    return URI.create("http://localhost:" + port + "/").resolve(location);
  }

  private static String location(HttpResponse<?> response) {
    return response.headers().firstValue("Location")
        .orElseThrow(() -> new AssertionError("no Location on " + response.statusCode()));
  }

  private static Map<String, String> query(URI uri) {
    return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap()
        .entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey,
            e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Sends with a hand-kept cookie jar per server. The JDK's CookieManager would drop
   * Keycloak's {@code Secure} session cookies on this plain-http test connection, and the
   * form post would then fail with "cookie not found".
   */
  private HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> jar)
      throws Exception {
    if (!jar.isEmpty()) {
      request.header("Cookie", jar.entrySet().stream()
          .map(e -> e.getKey() + "=" + e.getValue())
          .collect(Collectors.joining("; ")));
    }
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    for (String setCookie : response.headers().allValues("Set-Cookie")) {
      String pair = setCookie.split(";", 2)[0];
      int eq = pair.indexOf('=');
      if (eq > 0) {
        jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
      }
    }
    return response;
  }
}
