package org.opentmf.cadenzaflow.config.sso;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import org.cadenzaflow.bpm.engine.impl.util.ClockUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorize or re-authorize (if required) oauth2 client using {@link
 * OAuth2AuthorizedClientManager}.
 *
 * <ul>
 *   <li>If the access token is valid, then does nothing.
 *   <li>If the access token is expired, then refreshes it.
 *   <li>If authorize failed, then clears the {@link
 *       org.springframework.security.core.context.SecurityContext} and {@link
 *       jakarta.servlet.http.HttpSession}.
 * </ul>
 *
 * <p>References:
 *
 * <ul>
 *   <li>{@link OAuth2AuthorizedClientManager#authorize(OAuth2AuthorizeRequest)}
 *   <li>{@link
 *       org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider#authorize(OAuth2AuthorizationContext)}
 *   <li>{@link
 *       org.springframework.security.oauth2.client.DelegatingOAuth2AuthorizedClientProvider#authorize(OAuth2AuthorizationContext)}
 *   <li>{@link
 *       org.springframework.security.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider#authorize(OAuth2AuthorizationContext)}
 * </ul>
 *
 * @author Abdullah Beker
 */
public class AuthorizeTokenFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AuthorizeTokenFilter.class);
  private final OAuth2AuthorizedClientManager clientManager;

  public AuthorizeTokenFilter(OAuth2AuthorizedClientManager clientManager) {
    this.clientManager = clientManager;
  }

  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain)
      throws ServletException, IOException {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof OAuth2AuthenticationToken token) {
      authorizeToken(token, request, response);
    }
    filterChain.doFilter(request, response);
  }

  protected boolean hasTokenExpired(OAuth2Token token) {
    return token.getExpiresAt() == null || ClockUtil.now().after(Date.from(token.getExpiresAt()));
  }

  protected void clearContext(HttpServletRequest request) {
    SecurityContextHolder.clearContext();
    try {
      request.getSession().invalidate();
    } catch (Exception _) {
      // the session is already invalid or does not exist - nothing left to clean up
    }
  }

  protected void authorizeToken(
      OAuth2AuthenticationToken token, HttpServletRequest request, HttpServletResponse response) {
    var authRequest =
        OAuth2AuthorizeRequest.withClientRegistrationId(token.getAuthorizedClientRegistrationId())
            .principal(token)
            .attributes(
                attrs -> {
                  attrs.put(HttpServletRequest.class.getName(), request);
                  attrs.put(HttpServletResponse.class.getName(), response);
                })
            .build();

    var name = token.getName();
    try {
      var res = clientManager.authorize(authRequest);
      if (res == null || hasTokenExpired(res.getAccessToken())) {
        log.warn("Authorize failed for '{}': could not re-authorize expired access token", name);
        clearContext(request);
      } else {
        log.debug(
            "Authorize successful for '{}', access token expiry: {}",
            name,
            res.getAccessToken().getExpiresAt());
      }
    } catch (OAuth2AuthorizationException e) {
      log.warn("Authorize failed for '{}': {}", name, e.getMessage());
      clearContext(request);
    }
  }
}
