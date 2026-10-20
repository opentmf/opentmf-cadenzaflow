package org.opentmf.cadenzaflow.config.identity.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cadenzaflow.bpm.engine.IdentityService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.authorization.Resources;
import org.cadenzaflow.bpm.engine.identity.Group;
import org.cadenzaflow.bpm.engine.identity.User;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit coverage for the token identity provider: the signed-in principal is the ONLY
 * user the engine can see (pentest V02 - user enumeration - impossible by construction),
 * groups map from the token's authorities (Entra App Roles shape and a prefixed
 * Keycloak-converter shape), passwords never check, and off-request threads see nobody.
 * The engine-level proof boots a real H2 engine with the plugin and the admin-grant
 * self-heal on.
 */
class TokenIdentityProviderTests {

  private final TokenIdentityProviderPlugin plugin = new TokenIdentityProviderPlugin();
  private final TokenIdentityProviderSession session = new TokenIdentityProviderSession(plugin);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // -------------------------------------------------------------------------
  // Users: self only
  // -------------------------------------------------------------------------

  @Test
  void theSignedInPrincipalIsTheOnlyUserThereIs() {
    signIn("jane@vf.com", "dnms-read");

    List<User> users = session.findUsers(new TokenUserQuery());
    assertThat(users).hasSize(1);
    assertThat(users.get(0).getId()).isEqualTo("jane@vf.com");
    assertThat(session.findUserById("jane@vf.com")).isNotNull();
  }

  @Test
  void otherUsersAreInvisibleWhateverTheQuery_v02ImpossibleByConstruction() {
    signIn("jane@vf.com", "dnms-read");

    assertThat(session.findUserById("someone-else")).isNull();
    TokenUserQuery byId = new TokenUserQuery();
    byId.userId("someone-else");
    assertThat(session.findUsers(byId)).isEmpty();
    TokenUserQuery byEmail = new TokenUserQuery();
    byEmail.userEmail("someone-else@vf.com");
    assertThat(session.findUsers(byEmail)).isEmpty();
  }

  @Test
  void offRequestThreadsSeeNobodyAndNothingFails() {
    SecurityContextHolder.clearContext();

    assertThat(session.currentUser()).isNull();
    assertThat(session.findUsers(new TokenUserQuery())).isEmpty();
    assertThat(session.findGroups(new TokenGroupQuery())).isEmpty();
  }

  @Test
  void passwordsNeverCheck() {
    signIn("jane@vf.com", "dnms-read");
    assertThat(session.checkPassword("jane@vf.com", "any")).isFalse();
    assertThat(session.checkPassword("jane@vf.com", null)).isFalse();
  }

  // -------------------------------------------------------------------------
  // Groups: the token's authorities
  // -------------------------------------------------------------------------

  @Test
  void entraAppRolesArriveAsGroupsUnchanged() {
    // authorities-claim: roles - App Role values land as plain authorities.
    signIn("jane@vf.com", "dnms-read", "APP-DE-AWS-DNMS-DEV_CAMUNDA");

    List<Group> groups = session.findGroups(new TokenGroupQuery());
    assertThat(groups).extracting(Group::getId)
        .containsExactlyInAnyOrder("dnms-read", "APP-DE-AWS-DNMS-DEV_CAMUNDA");

    TokenGroupQuery mine = new TokenGroupQuery();
    mine.groupMember("jane@vf.com");
    assertThat(session.findGroups(mine)).hasSize(2);

    TokenGroupQuery someoneElses = new TokenGroupQuery();
    someoneElses.groupMember("someone-else");
    assertThat(session.findGroups(someoneElses)).isEmpty();
  }

  @Test
  void aConfiguredAuthorityPrefixIsStripped() {
    // A Keycloak-style converter that prefixes roles: the prefix is configuration.
    plugin.setAuthorityPrefix("ROLE_");
    signIn("jane@vf.com", "ROLE_offline-access", "dnms-read");

    assertThat(session.groupIds()).containsExactlyInAnyOrder("offline-access", "dnms-read");
  }

  @Test
  void theAdministratorGroupResolvesEvenWithoutARequest() {
    // The self-heal and getAdminGroups() consumers look the group up off-request.
    plugin.setAdministratorGroupName("dnms-platform-admin");
    SecurityContextHolder.clearContext();

    assertThat(session.findGroupById("dnms-platform-admin")).isNotNull();
    assertThat(session.findGroupById("anything-else")).isNull();
  }

  // -------------------------------------------------------------------------
  // The engine-level proof: a real H2 boot
  // -------------------------------------------------------------------------

  @Test
  void aRealEngineKnowsOnlyTheSignedInUserAndHealsTheAdminGrants() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("tokenIdentity-" + System.nanoTime());
    configuration.setJobExecutorActivate(false);
    configuration.setAuthorizationEnabled(true);
    TokenIdentityProviderPlugin tokenPlugin = new TokenIdentityProviderPlugin();
    tokenPlugin.setAdministratorGroupName("dnms-platform-admin");
    configuration.setProcessEnginePlugins(List.of(tokenPlugin));
    ProcessEngine engine = configuration.buildProcessEngine();
    try {
      assertThat(configuration.getAdminGroups()).contains("dnms-platform-admin");

      // The self-heal granted ALL on every resource to the administrator group.
      assertThat(
              engine.getAuthorizationService().createAuthorizationQuery()
                  .groupIdIn("dnms-platform-admin").count())
          .isEqualTo(Resources.values().length);

      IdentityService identityService = engine.getIdentityService();
      signIn("jane@vf.com", "dnms-read", "dnms-platform-admin");
      assertThat(identityService.createUserQuery().count()).isEqualTo(1);
      assertThat(identityService.createUserQuery().list().get(0).getId())
          .isEqualTo("jane@vf.com");
      assertThat(identityService.createUserQuery().userId("intruder").count()).isZero();
      assertThat(
              identityService.createGroupQuery().groupMember("jane@vf.com").list())
          .extracting(Group::getId)
          .containsExactlyInAnyOrder("dnms-read", "dnms-platform-admin");
      assertThat(identityService.checkPassword("jane@vf.com", "secret")).isFalse();
      assertThat(identityService.createTenantQuery().count()).isZero();
    } finally {
      engine.close();
    }
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static void signIn(String name, String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(name, "n/a", authorities));
  }
}
