package org.opentmf.cadenzaflow.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.cadenzaflow.bpm.engine.AuthorizationException;
import org.cadenzaflow.bpm.engine.AuthorizationService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.authorization.Authorization;
import org.cadenzaflow.bpm.engine.authorization.Permissions;
import org.cadenzaflow.bpm.engine.authorization.Resources;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.impl.cmd.SaveAuthorizationCmd;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandExecutor;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opentmf.cadenzaflow.config.auth.AdminAuthorizationGuardPlugin.AdminGuardedAuthorizationService;

/**
 * Unit coverage for {@link AdminAuthorizationGuardPlugin}: the preInit swap, both
 * refusals (re-pointing and deleting the administrator group's grant), and the allowed
 * cases - a no-op save, a non-admin grant's full mutability, a user grant, a NEW grant
 * for the admin group (the restart self-heal's path), and the lazy admin-group read.
 * The engine-level proof against a real database runs in the same file's H2 boot test.
 */
class AdminAuthorizationGuardPluginTests {

  /**
   * The engine's own default admin group - in this fork {@code Groups.CAMUNDA_ADMIN}
   * is {@code "cadenzaflow-admin"}, so the constant is used, never a literal.
   */
  private static final String ADMIN_GROUP =
      org.cadenzaflow.bpm.engine.authorization.Groups.CAMUNDA_ADMIN;

  // -------------------------------------------------------------------------
  // The preInit swap
  // -------------------------------------------------------------------------

  @Test
  void preInitInstallsTheGuardedService() {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);

    new AdminAuthorizationGuardPlugin().preInit(configuration);

    ArgumentCaptor<AuthorizationService> captor =
        ArgumentCaptor.forClass(AuthorizationService.class);
    verify(configuration).setAuthorizationService(captor.capture());
    assertThat(captor.getValue()).isInstanceOf(AdminGuardedAuthorizationService.class);
  }

  // -------------------------------------------------------------------------
  // Refusals
  // -------------------------------------------------------------------------

  @Test
  void repointingTheAdminGroupsGrantIsRefused() {
    AdminGuardedAuthorizationService service =
        serviceWith(grant(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2));
    Authorization update = grantWithId(ADMIN_GROUP + " ghost",
        Resources.APPLICATION.resourceType(), "*", 2);

    assertThatThrownBy(() -> service.saveAuthorization(update))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("administrator group's own authorizations are protected");
  }

  @Test
  void changingTheAdminGrantsResourceOrPermissionsIsRefused() {
    AdminGuardedAuthorizationService service =
        serviceWith(grant(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2));

    Authorization resourceChange =
        grantWithId(ADMIN_GROUP, Resources.PROCESS_DEFINITION.resourceType(), "*", 2);
    assertThatThrownBy(() -> service.saveAuthorization(resourceChange))
        .isInstanceOf(AuthorizationException.class);

    Authorization resourceIdChange =
        grantWithId(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "cockpit", 2);
    assertThatThrownBy(() -> service.saveAuthorization(resourceIdChange))
        .isInstanceOf(AuthorizationException.class);

    Authorization permissionChange =
        grantWithId(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 0);
    assertThatThrownBy(() -> service.saveAuthorization(permissionChange))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  void deletingTheAdminGroupsGrantIsRefused() {
    AdminGuardedAuthorizationService service =
        serviceWith(grant(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2));

    assertThatThrownBy(() -> service.deleteAuthorization("stored-id"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("administrator group's own authorizations are protected");
  }

  // -------------------------------------------------------------------------
  // Allowed
  // -------------------------------------------------------------------------

  @Test
  void aNoOpSaveOfTheAdminGrantPasses() {
    AdminGuardedAuthorizationService service =
        serviceWith(grant(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2));
    CommandExecutor commandExecutor = executorOn(service);
    Authorization unchanged = grantWithId(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2);

    assertThatCode(() -> service.saveAuthorization(unchanged)).doesNotThrowAnyException();
    verify(commandExecutor).execute(org.mockito.ArgumentMatchers.any(SaveAuthorizationCmd.class));
  }

  @Test
  void aNonAdminGroupGrantStaysFullyMutable() {
    AdminGuardedAuthorizationService service =
        serviceWith(grant("ebu-federation-writer", Resources.APPLICATION.resourceType(), "*", 2));
    executorOn(service);
    Authorization repointed =
        grantWithId("someone-else", Resources.PROCESS_DEFINITION.resourceType(), "x", 0);

    assertThatCode(() -> service.saveAuthorization(repointed)).doesNotThrowAnyException();
    assertThatCode(() -> service.deleteAuthorization("stored-id")).doesNotThrowAnyException();
  }

  @Test
  void aUserGrantIsNotTheGuardsBusiness() {
    AuthorizationEntity stored = grant(null, Resources.APPLICATION.resourceType(), "*", 2);
    stored.setUserId("jane");
    AdminGuardedAuthorizationService service = serviceWith(stored);
    executorOn(service);

    assertThatCode(() -> service.deleteAuthorization("stored-id")).doesNotThrowAnyException();
  }

  @Test
  void aNewGrantForTheAdminGroupPasses() {
    // The restart self-heal creates missing grants; its path must stay open.
    AdminGuardedAuthorizationService service =
        new AdminGuardedAuthorizationService(configurationWithAdminGroups(ADMIN_GROUP)) {
          @Override
          protected Authorization findExisting(String authorizationId) {
            throw new AssertionError("a NEW grant (no id) must not be looked up");
          }
        };
    executorOn(service);
    AuthorizationEntity fresh = grant(ADMIN_GROUP, Resources.APPLICATION.resourceType(), "*", 2);
    fresh.setId(null);

    assertThatCode(() -> service.saveAuthorization(fresh)).doesNotThrowAnyException();
  }

  @Test
  void adminGroupsAreReadAtCallTimeNotSnapshotted() {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    // Empty at construction (= preInit time), filled later by the identity plugin.
    when(configuration.getAdminGroups())
        .thenReturn(List.of())
        .thenReturn(List.of("late-admin-group"));
    AuthorizationEntity stored = grant("late-admin-group",
        Resources.APPLICATION.resourceType(), "*", 2);
    AdminGuardedAuthorizationService service =
        new AdminGuardedAuthorizationService(configuration) {
          @Override
          protected Authorization findExisting(String authorizationId) {
            return stored;
          }
        };
    executorOn(service);

    // First call: the group is not an admin group yet - mutation passes.
    assertThatCode(() -> service.deleteAuthorization("stored-id")).doesNotThrowAnyException();
    // Second call: the plugin has registered the group - now protected.
    assertThatThrownBy(() -> service.deleteAuthorization("stored-id"))
        .isInstanceOf(AuthorizationException.class);
  }

  // -------------------------------------------------------------------------
  // The engine-level proof: a real H2 boot with the plugin on
  // -------------------------------------------------------------------------

  @Test
  void aRealEngineRefusesTheV01EditAndTheGrantSurvives() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("adminGuard-" + System.nanoTime());
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEnginePlugins(List.of(new AdminAuthorizationGuardPlugin()));
    ProcessEngine engine = configuration.buildProcessEngine();
    try {
      AuthorizationService authorizationService = engine.getAuthorizationService();
      assertThat(configuration.getAdminGroups()).contains(ADMIN_GROUP);

      Authorization grant =
          authorizationService.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
      grant.setGroupId(ADMIN_GROUP);
      grant.setResource(Resources.APPLICATION);
      grant.setResourceId("*");
      grant.addPermission(Permissions.ALL);
      authorizationService.saveAuthorization(grant);

      // V01: re-point the admin group's grant to a group that does not exist.
      Authorization loaded = authorizationService.createAuthorizationQuery()
          .authorizationId(grant.getId()).singleResult();
      loaded.setGroupId("camunda-admin <img src=x onerror=confirm(1)>");
      assertThatThrownBy(() -> authorizationService.saveAuthorization(loaded))
          .isInstanceOf(AuthorizationException.class)
          .hasMessageContaining("administrator group's own authorizations are protected");

      assertThatThrownBy(() -> authorizationService.deleteAuthorization(grant.getId()))
          .isInstanceOf(AuthorizationException.class);

      Authorization survived = authorizationService.createAuthorizationQuery()
          .authorizationId(grant.getId()).singleResult();
      assertThat(survived.getGroupId()).isEqualTo(ADMIN_GROUP);
    } finally {
      engine.close();
    }
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  /** A guarded service whose stored row and admin groups are canned. */
  private static AdminGuardedAuthorizationService serviceWith(AuthorizationEntity stored) {
    return new AdminGuardedAuthorizationService(configurationWithAdminGroups(ADMIN_GROUP)) {
      @Override
      protected Authorization findExisting(String authorizationId) {
        return stored;
      }
    };
  }

  private static ProcessEngineConfigurationImpl configurationWithAdminGroups(String... groups) {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getAdminGroups()).thenReturn(List.of(groups));
    return configuration;
  }

  private static CommandExecutor executorOn(AdminGuardedAuthorizationService service) {
    CommandExecutor commandExecutor = mock(CommandExecutor.class);
    service.setCommandExecutor(commandExecutor);
    return commandExecutor;
  }

  private static AuthorizationEntity grant(
      String groupId, int resourceType, String resourceId, int permissions) {
    AuthorizationEntity entity = new AuthorizationEntity(Authorization.AUTH_TYPE_GRANT);
    entity.setId("stored-id");
    entity.setGroupId(groupId);
    entity.setResourceType(resourceType);
    entity.setResourceId(resourceId);
    entity.setPermissions(permissions);
    return entity;
  }

  private static Authorization grantWithId(
      String groupId, int resourceType, String resourceId, int permissions) {
    return grant(groupId, resourceType, resourceId, permissions);
  }
}
