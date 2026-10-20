package org.opentmf.cadenzaflow;

import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.identity.Group;
import org.cadenzaflow.bpm.engine.identity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full application with {@code plugin.identity.provider=token} - no directory
 * behind the engine at all. Covers what the plugin-level tests cannot: yml property
 * binding, provider-switch bean selection (Keycloak dormant), engine mounting, the
 * startup admin bootstrap against the real (Testcontainers Postgres) database, and
 * identity queries through the engine's command context, where the signed-in principal
 * is the only user the engine can see.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class TokenIdentityIT {

  private static final String EMAIL = "jane@vf.com";

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    r.add("plugin.identity.provider", () -> "token");
    r.add("plugin.identity.token.administrator-group-name", () -> "dnms-platform-admin");
    r.add("plugin.identity.token.authority-prefix", () -> "ROLE_");
  }

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private ProcessEngine processEngine;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void providerSwitchActivatesTokenAndLeavesKeycloakDormant() {
    Assertions.assertEquals(1,
        applicationContext.getBeanNamesForType(
            org.opentmf.cadenzaflow.config.TokenIdentityProvider.class).length);
    Assertions.assertEquals(0,
        applicationContext.getBeanNamesForType(
            org.opentmf.cadenzaflow.config.KeycloakIdentityProvider.class).length);
  }

  @Test
  void ymlPropertiesBindToThePluginConfiguration() {
    var plugin =
        applicationContext.getBean(org.opentmf.cadenzaflow.config.TokenIdentityProvider.class);
    Assertions.assertEquals("dnms-platform-admin", plugin.getAdministratorGroupName());
    Assertions.assertEquals("ROLE_", plugin.getAuthorityPrefix());
  }

  @Test
  void startupBootstrapGrantsAdminAuthorizationsInTheDatabase() {
    // postProcessEngineBuild resolved 'dnms-platform-admin' off-request and persisted grants
    Assertions.assertTrue(processEngine.getAuthorizationService().createAuthorizationQuery()
        .groupIdIn("dnms-platform-admin").count() > 0);
    Assertions.assertTrue(((org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl)
        processEngine.getProcessEngineConfiguration()).getAdminGroups()
        .contains("dnms-platform-admin"));
  }

  @Test
  void engineSeesOnlyTheSignedInPrincipalViaCommandContext() {
    signIn(EMAIL, "ROLE_dnms-read");

    User user = processEngine.getIdentityService().createUserQuery().userId(EMAIL).singleResult();
    Assertions.assertNotNull(user);
    Assertions.assertEquals(EMAIL, user.getId());
    Assertions.assertEquals(1, processEngine.getIdentityService().createUserQuery().count());
    // user enumeration is impossible by construction (pentest V02)
    Assertions.assertEquals(0,
        processEngine.getIdentityService().createUserQuery().userId("intruder").count());
  }

  @Test
  void engineResolvesGroupsFromTheTokenAuthoritiesWithThePrefixStripped() {
    signIn(EMAIL, "ROLE_dnms-read", "ROLE_dnms-platform-admin");

    var groups = processEngine.getIdentityService().createGroupQuery().groupMember(EMAIL).list();
    Assertions.assertEquals(2, groups.size());
    Assertions.assertTrue(groups.stream().map(Group::getId).toList()
        .containsAll(java.util.List.of("dnms-read", "dnms-platform-admin")));
  }

  @Test
  void passwordLoginIsRejectedTokenOnly() {
    signIn(EMAIL, "ROLE_dnms-read");
    Assertions.assertFalse(
        processEngine.getIdentityService().checkPassword(EMAIL, "any-password"));
  }

  private static void signIn(String name, String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(name, "n/a", authorities));
  }
}
