package org.opentmf.cadenzaflow;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ENGINE-JOBS-IDLE-IN-TRANSACTION-1 on the image's pool (auto-commit off, no Hikari
 * {@code schema}): no session of the engine's role stays idle in transaction for
 * {@value EngineIdleInTransactionStressScenario#FINDING_AGE_SECONDS} s or longer - at rest,
 * under the burst, in the drain. See {@link EngineIdleInTransactionStressScenario}.
 *
 * @author Cezmi Aslan
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
class EngineIdleInTransactionStressIT extends EngineIdleInTransactionStressScenario {

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
  void theEngineLeavesNoSessionIdleInTransactionUnderABurstOfJobs() throws Exception {
    assertThat(pool().getSchema())
        .as("no Hikari schema, as the image ships it (CF-SCHEMA-FROM-URL-1)").isNull();

    assertThat(runPhases())
        .as("no session of the engine's role is idle in transaction for %d s or longer",
            FINDING_AGE_SECONDS)
        .hasSize(3)
        .allSatisfy(phase -> assertThat(phase.findings).as(phase.name).isEmpty());
  }
}
