package org.opentmf.cadenzaflow;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
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

  @Test
  void contextLoads() {
    Assertions.assertNotNull(applicationContext);
  }
}
