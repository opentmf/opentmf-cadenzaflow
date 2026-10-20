package org.opentmf.cadenzaflow.config.identity.token;

import static org.cadenzaflow.bpm.engine.authorization.Authorization.ANY;
import static org.cadenzaflow.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.cadenzaflow.bpm.engine.authorization.Permissions.ALL;

import java.util.ArrayList;
import org.cadenzaflow.bpm.engine.AuthorizationService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.authorization.Resource;
import org.cadenzaflow.bpm.engine.authorization.Resources;
import org.cadenzaflow.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token-based identity: the engine knows exactly one user - the signed-in principal of
 * the current request's Spring Security context - and that user's groups are the token's
 * authorities. There is NO directory behind it: no Graph, no Keycloak Admin API, and
 * therefore no {@code User.Read.All}-style permission and no user enumeration, whatever
 * the caller's role (pentest finding V02 is impossible by construction).
 *
 * <p>Selected with {@code plugin.identity.provider: token}. Trade-offs, documented in
 * the README: the Admin console's user and group pages show only the signed-in user,
 * authorizations are written against group names typed by hand, and Tasklist cannot
 * search a directory to assign tasks. Threads without a request - the job executor
 * first among them - see no principal and get empty query results.</p>
 *
 * <p>The administrator group (when configured) is registered in the engine's admin
 * groups and granted {@code ALL} on every resource at build, exactly like the
 * directory-backed plugins do, so the restart self-heal and the admin-authorization
 * guard keep working unchanged.</p>
 */
public class TokenIdentityProviderPlugin extends AbstractProcessEnginePlugin {

  private static final Logger log = LoggerFactory.getLogger(TokenIdentityProviderPlugin.class);

  /** The group granted ALL on every resource at build; empty = no extra admin group. */
  private String administratorGroupName;

  /**
   * An authority prefix to strip when mapping token authorities to engine groups (e.g.
   * {@code ROLE_} for converters that add one). Empty by default: Entra App Roles via an
   * {@code authorities-claim: roles} mapping and Keycloak groups/realm roles via
   * {@code authorities-claim: groups} both arrive unprefixed.
   */
  private String authorityPrefix = "";

  private boolean authorizationEnabled;

  @Override
  public void preInit(ProcessEngineConfigurationImpl engineConfig) {
    authorizationEnabled = engineConfig.isAuthorizationEnabled();
    if (engineConfig.getAdminGroups() == null) {
      engineConfig.setAdminGroups(new ArrayList<>());
    }
    engineConfig.setIdentityProviderSessionFactory(new TokenIdentityProviderFactory(this));
    log.info("Token identity provider activated: users resolve to the signed-in principal only");
  }

  @Override
  public void postProcessEngineBuild(ProcessEngine processEngine) {
    if (administratorGroupName == null || administratorGroupName.isBlank()) {
      return;
    }
    ProcessEngineConfigurationImpl engineConfig =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    engineConfig.getAdminGroups().add(administratorGroupName);
    if (!authorizationEnabled) {
      return;
    }
    // The restart self-heal, same shape as the directory-backed plugins: any missing
    // ALL grant for the administrator group is re-created on every boot.
    AuthorizationService authorizationService = processEngine.getAuthorizationService();
    for (Resource resource : Resources.values()) {
      if (authorizationService.createAuthorizationQuery()
              .groupIdIn(administratorGroupName)
              .resourceType(resource)
              .resourceId(ANY)
              .count()
          == 0) {
        AuthorizationEntity grant = new AuthorizationEntity(AUTH_TYPE_GRANT);
        grant.setGroupId(administratorGroupName);
        grant.setResource(resource);
        grant.setResourceId(ANY);
        grant.addPermission(ALL);
        authorizationService.saveAuthorization(grant);
        log.info(
            "Granted ALL on {} to administrator group '{}'",
            resource.resourceName(),
            administratorGroupName);
      }
    }
  }

  public String getAdministratorGroupName() {
    return administratorGroupName;
  }

  public void setAdministratorGroupName(String administratorGroupName) {
    this.administratorGroupName = administratorGroupName;
  }

  public String getAuthorityPrefix() {
    return authorityPrefix;
  }

  public void setAuthorityPrefix(String authorityPrefix) {
    this.authorityPrefix = authorityPrefix == null ? "" : authorityPrefix;
  }
}
