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
import org.springframework.security.core.Authentication;
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
  void theIdListFilterConstrainsTheSelfAnswerBothWays() {
    signIn("jane@vf.com", "dnms-read");

    TokenUserQuery inList = new TokenUserQuery();
    inList.userIdIn("jane@vf.com", "someone-else");
    assertThat(session.findUsers(inList)).extracting(User::getId).containsExactly("jane@vf.com");

    TokenUserQuery notInList = new TokenUserQuery();
    notInList.userIdIn("other-a", "other-b");
    assertThat(session.findUsers(notInList)).isEmpty();
  }

  @Test
  void theGroupFilterConstrainsTheSelfAnswerBothWays() {
    signIn("jane@vf.com", "dnms-read");

    TokenUserQuery myGroup = new TokenUserQuery();
    myGroup.memberOfGroup("dnms-read");
    assertThat(session.findUsers(myGroup)).extracting(User::getId).containsExactly("jane@vf.com");

    TokenUserQuery otherGroup = new TokenUserQuery();
    otherGroup.memberOfGroup("not-my-group");
    assertThat(session.findUsers(otherGroup)).isEmpty();
  }

  @Test
  void theEmailIsThePrincipalNameAndTheEmailFilterMatchesIt() {
    signIn("jane@vf.com", "dnms-read");

    assertThat(session.currentUser().getEmail()).isEqualTo("jane@vf.com");
    TokenUserQuery byMyEmail = new TokenUserQuery();
    byMyEmail.userEmail("jane@vf.com");
    assertThat(session.findUsers(byMyEmail)).extracting(User::getId)
        .containsExactly("jane@vf.com");
  }

  @Test
  void anUnauthenticatedTokenIsNobody() {
    // isAuthenticated() false is its own branch, distinct from no authentication at
    // all - and the token CARRIES authorities, so a provider that skipped the check
    // would hand them out as groups.
    TestingAuthenticationToken token =
        new TestingAuthenticationToken("jane@vf.com", "n/a", "dnms-read", "dnms-platform-admin");
    token.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(token);

    assertThat(session.currentUser()).isNull();
    assertThat(session.findUsers(new TokenUserQuery())).isEmpty();
    assertThat(session.groupIds()).isEmpty();
  }

  @Test
  void anAuthenticatedTokenWithoutANameIsNobodyToo() {
    // TestingAuthenticationToken can never answer getName() == null (a null principal
    // becomes ""), so this branch needs a stubbed Authentication: authenticated, but
    // nameless - the engine must not mint a user with a null id from it.
    Authentication nameless = org.mockito.Mockito.mock(Authentication.class);
    org.mockito.Mockito.when(nameless.isAuthenticated()).thenReturn(true);
    org.mockito.Mockito.when(nameless.getName()).thenReturn(null);
    SecurityContextHolder.getContext().setAuthentication(nameless);

    assertThat(session.currentUser()).isNull();
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

  @Test
  void groupQueriesFilterByIdAndIdListBothWays() {
    signIn("jane@vf.com", "dnms-read", "dnms-write");

    TokenGroupQuery byId = new TokenGroupQuery();
    byId.groupId("dnms-read");
    assertThat(session.findGroups(byId)).extracting(Group::getId).containsExactly("dnms-read");

    TokenGroupQuery byWrongId = new TokenGroupQuery();
    byWrongId.groupId("not-mine");
    assertThat(session.findGroups(byWrongId)).isEmpty();

    TokenGroupQuery byIds = new TokenGroupQuery();
    byIds.groupIdIn("dnms-read", "someone-elses");
    assertThat(session.findGroups(byIds)).extracting(Group::getId).containsExactly("dnms-read");

    TokenGroupQuery byWrongIds = new TokenGroupQuery();
    byWrongIds.groupIdIn("other-a", "other-b");
    assertThat(session.findGroups(byWrongIds)).isEmpty();
  }

  @Test
  void aNullGroupIdResolvesToNothingEvenSignedIn() {
    signIn("jane@vf.com", "dnms-read");
    assertThat(session.findGroupById(null)).isNull();
  }

  @Test
  void aTokenGroupResolvesByIdWithItsNameAndType() {
    signIn("jane@vf.com", "dnms-read");

    Group group = session.findGroupById("dnms-read");
    assertThat(group).isNotNull();
    assertThat(group.getName()).isEqualTo("dnms-read");
    assertThat(group.getType()).isEqualTo("TOKEN");
  }

  @Test
  void theCommandContextFactoriesHandOutTheTokenQueries() {
    // The engine calls these inside a command; the context argument is unused here.
    assertThat(session.createUserQuery(null)).isInstanceOf(TokenUserQuery.class);
    assertThat(session.createGroupQuery(null)).isInstanceOf(TokenGroupQuery.class);
    assertThat(session.createTenantQuery(null)).isInstanceOf(TokenTenantQuery.class);
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
