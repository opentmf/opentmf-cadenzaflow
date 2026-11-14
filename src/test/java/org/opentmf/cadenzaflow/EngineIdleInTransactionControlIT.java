package org.opentmf.cadenzaflow;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The control of {@link EngineIdleInTransactionStressIT}: the same scenario with the Hikari
 * {@code schema} of engine 1.4.1 put back. With auto-commit off, the pool's {@code setSchema}
 * on a new connection opens a transaction nobody commits, so a pooled connection the engine
 * has not borrowed yet sits idle in transaction - the shape the 2026-10-13 load run saw. The
 * sampler must report it; if it reported nothing here, the clean run would prove nothing.
 *
 * @author Cezmi Aslan
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
// The Hikari schema of engine 1.4.1, the cause CF-SCHEMA-FROM-URL-1 removed in 1.5.0.
@TestPropertySource(properties = "spring.datasource.hikari.schema=public")
class EngineIdleInTransactionControlIT extends EngineIdleInTransactionStressScenario {

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

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    r.add("plugin.identity.keycloak.keycloak-issuer-url", () -> String.format(
        "http://%s:%d/realms/dsync", KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080)));
    r.add("plugin.identity.keycloak.keycloak-admin-url", () -> String.format(
        "http://%s:%d/admin/realms/dsync", KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080)));
  }

  @Test
  void theSamplerSeesTheIdleTransactionsOfTheHikariSchemaSetting() throws Exception {
    assertThat(pool().getSchema()).isEqualTo("public");

    assertThat(runPhases())
        .as("the 1.4.1 pool setting leaves sessions idle in transaction, and the sampler sees them")
        .anySatisfy(phase -> assertThat(phase.findings).as(phase.name).isNotEmpty());
  }
}
