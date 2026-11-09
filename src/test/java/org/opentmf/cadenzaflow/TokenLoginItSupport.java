package org.opentmf.cadenzaflow;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

/**
 * Shared set-up of the token-login boot tests (CF-ID-TOKEN-DECODER-BOOT-1): each subclass
 * boots the whole application in one deployment shape of {@code opentmf.security} with the
 * shared sign-in switched on, and signs in through {@code POST /app/login-with-token}.
 * Booting is half the test: 1.5.3 failed to create {@code loginWithTokenFilterChain} on
 * a stack shape no test booted.
 */
abstract class TokenLoginItSupport {

  /** The webapp's OIDC client registration (application-it.yml). */
  static final String CLIENT_ID = "camunda-identity-service";
  /** The shared page's own console client, as on a DNMS Keycloak stack. */
  static final String CONSOLE_CLIENT_ID = "dnms-config-ui";

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

  static final LoginWithTokenIT.TestIssuer ISSUER = new LoginWithTokenIT.TestIssuer();

  @LocalServerPort
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  /** The webapp side: Keycloak as directory and provider, the shared sign-in on. */
  static void sharedSignIn(DynamicPropertyRegistry r) {
    var issuer = String.format("http://%s:%d/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    var admin = String.format("http://%s:%d/admin/realms/dsync",
        KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    r.add("plugin.identity.keycloak.keycloak-issuer-url", () -> issuer);
    r.add("plugin.identity.keycloak.keycloak-admin-url", () -> admin);
    r.add("cadenzaflow.sso.shared-callback-url", () -> "https://ui.example.test/auth/callback");
    r.add("cadenzaflow.sso.login-page-url", () -> "https://ui.example.test/auth/cockpit");
    r.add("cadenzaflow.sso.bearer-login.user-claim", () -> "email");
  }

  HttpResponse<String> login(String accessToken, String idToken) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/cadenzaflow/v1/app/login-with-token"))
        .header("Authorization", "Bearer " + accessToken)
        .header("X-ID-Token", idToken)
        .header("Origin", "http://localhost:" + port)
        .header("Sec-Fetch-Site", "same-origin")
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  static Optional<String> sessionCookie(HttpResponse<?> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .map(c -> c.split(";", 2)[0])
        .filter(c -> c.startsWith("JSESSIONID=") && c.length() > "JSESSIONID=".length())
        .findFirst();
  }
}
