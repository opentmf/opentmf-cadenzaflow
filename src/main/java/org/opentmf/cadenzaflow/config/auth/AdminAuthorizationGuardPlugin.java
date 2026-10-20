package org.opentmf.cadenzaflow.config.auth;

import java.util.List;
import java.util.Objects;
import org.cadenzaflow.bpm.engine.AuthorizationException;
import org.cadenzaflow.bpm.engine.authorization.Authorization;
import org.cadenzaflow.bpm.engine.impl.AuthorizationServiceImpl;
import org.cadenzaflow.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the administrator group from locking itself out (pentest finding V01): an
 * administrator - or, on {@code /engine-rest}, any caller the role rules let through -
 * could re-point or delete the administrator group's own grants, after which every
 * member of that group loses Cockpit, Admin and Tasklist until a restart re-creates
 * the missing grants.
 *
 * <p>The guard replaces the engine's {@link org.cadenzaflow.bpm.engine.AuthorizationService}
 * with a subclass of the stock implementation (a subclass, not a delegate, so
 * {@code initService} wires the command executor into it unchanged - the same preInit
 * swap this distribution already performs for the schema-operations command in
 * {@code JobPriorityIndexPlugin}). Every entry point that can touch authorizations -
 * the webapp's {@code /api}, {@code /engine-rest}'s authorization resource and the
 * Java API - goes through this service's two methods.</p>
 *
 * <p>Creating NEW grants for the administrator group stays allowed - that is the
 * restart self-heal's own path, and it remains the safety net.</p>
 */
@Configuration
public class AdminAuthorizationGuardPlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(ProcessEngineConfigurationImpl engineConfig) {
    engineConfig.setAuthorizationService(new AdminGuardedAuthorizationService(engineConfig));
  }

  /** The guarded service. Package-visible for the unit tests. */
  static class AdminGuardedAuthorizationService extends AuthorizationServiceImpl {

    static final String PROTECTED_MESSAGE =
        "the administrator group's own authorizations are protected";

    private final ProcessEngineConfigurationImpl engineConfig;

    AdminGuardedAuthorizationService(ProcessEngineConfigurationImpl engineConfig) {
      this.engineConfig = engineConfig;
    }

    @Override
    public Authorization saveAuthorization(Authorization authorization) {
      if (authorization != null && authorization.getId() != null) {
        Authorization stored = findExisting(authorization.getId());
        if (isAdminGroupGrant(stored) && changes(stored, authorization)) {
          throw new AuthorizationException(PROTECTED_MESSAGE);
        }
      }
      return super.saveAuthorization(authorization);
    }

    @Override
    public void deleteAuthorization(String authorizationId) {
      if (isAdminGroupGrant(findExisting(authorizationId))) {
        throw new AuthorizationException(PROTECTED_MESSAGE);
      }
      super.deleteAuthorization(authorizationId);
    }

    /** The stored row, loaded fresh - never the caller's (possibly mutated) instance. */
    protected Authorization findExisting(String authorizationId) {
      return createAuthorizationQuery().authorizationId(authorizationId).singleResult();
    }

    /**
     * Admin groups are read at CALL time, never snapshotted: the identity plugins add
     * the configured administrator group to {@code getAdminGroups()} only after this
     * service is installed in {@code preInit}.
     */
    private boolean isAdminGroupGrant(Authorization stored) {
      if (stored == null || stored.getGroupId() == null) {
        return false;
      }
      List<String> adminGroups = engineConfig.getAdminGroups();
      return adminGroups != null && adminGroups.contains(stored.getGroupId());
    }

    /** True when the update would change what the administrator group may do, or as whom. */
    private static boolean changes(Authorization stored, Authorization update) {
      if (!Objects.equals(stored.getGroupId(), update.getGroupId())
          || stored.getResourceType() != update.getResourceType()
          || !Objects.equals(stored.getResourceId(), update.getResourceId())) {
        return true;
      }
      if (stored instanceof AuthorizationEntity storedEntity
          && update instanceof AuthorizationEntity updateEntity) {
        return storedEntity.getPermissions() != updateEntity.getPermissions();
      }
      // Only AuthorizationEntity exists in practice. An implementation whose permission
      // bits cannot be read cannot be proven harmless, so it counts as a change - the
      // guard fails toward protection.
      return true;
    }
  }
}
