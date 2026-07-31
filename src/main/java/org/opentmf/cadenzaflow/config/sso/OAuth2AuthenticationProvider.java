package org.opentmf.cadenzaflow.config.sso;

import jakarta.servlet.http.HttpServletRequest;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cadenzaflow.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

/**
 * @author Abdullah Beker
 */
public class OAuth2AuthenticationProvider extends ContainerBasedAuthenticationProvider {

  private static final Logger logger = LoggerFactory.getLogger(OAuth2AuthenticationProvider.class);

  @Override
  public AuthenticationResult extractAuthenticatedUser(
      HttpServletRequest request, ProcessEngine engine) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null) {
      logger.debug("Authentication is null");
      return AuthenticationResult.unsuccessful();
    }

    if (!(authentication instanceof OAuth2AuthenticationToken oauth2)) {
      logger.debug("Authentication is not OAuth2, it is {}", authentication.getClass());
      return AuthenticationResult.unsuccessful();
    }
    String camundaUserId = oauth2.getName();
    if (camundaUserId.isEmpty()) {
      logger.debug("UserId is empty");
      return AuthenticationResult.unsuccessful();
    }

    logger.debug("Authenticated user '{}'", camundaUserId);
    return AuthenticationResult.successful(camundaUserId);
  }
}
