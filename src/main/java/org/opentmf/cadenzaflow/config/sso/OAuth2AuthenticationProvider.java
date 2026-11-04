package org.opentmf.cadenzaflow.config.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cadenzaflow.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider;
import org.cadenzaflow.bpm.webapp.impl.security.auth.AuthenticationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

/**
 * @author Abdullah Beker
 */
public class OAuth2AuthenticationProvider extends ContainerBasedAuthenticationProvider {

  private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationProvider.class);

  @Override
  public AuthenticationResult extractAuthenticatedUser(
      HttpServletRequest request, ProcessEngine engine) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null) {
      log.debug("Authentication is null");
      return AuthenticationResult.unsuccessful();
    }

    // OAuth2AuthenticationToken: the server-side OIDC login. BearerSessionAuthentication: a
    // session opened by POST /app/login-with-token (CF-SHARED-CALLBACK-3), whose name is
    // the user claim engine-rest maps its bearer tokens with.
    if (!(authentication instanceof OAuth2AuthenticationToken
        || authentication instanceof BearerSessionAuthentication)) {
      log.debug("Authentication is not OAuth2, it is {}", authentication.getClass());
      return AuthenticationResult.unsuccessful();
    }
    String camundaUserId = authentication.getName();
    if (camundaUserId.isEmpty()) {
      log.debug("UserId is empty");
      return AuthenticationResult.unsuccessful();
    }
    if (!knownToTheDirectory(request, engine, camundaUserId)) {
      // The engine webapp's filter would look the user up itself, get null and fail with an
      // NPE (500). Unsuccessful here makes it answer 401 instead.
      log.warn("Signed-in user '{}' is not in the engine's identity provider", camundaUserId);
      return AuthenticationResult.unsuccessful();
    }

    log.debug("Authenticated user '{}'", camundaUserId);
    return AuthenticationResult.successful(camundaUserId);
  }

  /**
   * Whether the engine's identity provider knows the user
   * (OPENTMF-CADENZAFLOW-UNKNOWN-DIRECTORY-USER-401-1). A session whose webapp authentication
   * for this engine already names the user was looked up before, so the directory is asked
   * only once per session, not on every request.
   */
  private static boolean knownToTheDirectory(
      HttpServletRequest request, ProcessEngine engine, String userId) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      org.cadenzaflow.bpm.webapp.impl.security.auth.Authentication existing =
          AuthenticationUtil.getAuthsFromSession(session)
              .getAuthenticationForProcessEngine(engine.getName());
      if (existing != null && userId.equals(existing.getName())) {
        return true;
      }
    }
    return engine.getIdentityService().createUserQuery().userId(userId).count() > 0;
  }
}
