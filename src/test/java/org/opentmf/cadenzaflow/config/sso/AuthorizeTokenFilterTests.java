package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class AuthorizeTokenFilterTests {

  private final OAuth2AuthorizedClientManager clientManager =
      mock(OAuth2AuthorizedClientManager.class);
  private final AuthorizeTokenFilter filter = new AuthorizeTokenFilter(clientManager);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final FilterChain chain = mock(FilterChain.class);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void keepsContextWhenReauthorizationSucceeds() throws ServletException, IOException {
    authenticate();
    OAuth2AuthorizedClient client = authorizedClient(Instant.now().plusSeconds(300));
    when(clientManager.authorize(any())).thenReturn(client);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNotNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void clearsContextWhenReauthorizationYieldsNoClient() throws ServletException, IOException {
    authenticate();
    when(clientManager.authorize(any())).thenReturn(null);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void clearsContextWhenReauthorizedTokenIsAlreadyExpired() throws ServletException, IOException {
    authenticate();
    OAuth2AuthorizedClient client = authorizedClient(Instant.now().minusSeconds(1));
    when(clientManager.authorize(any())).thenReturn(client);

    filter.doFilterInternal(request, response, chain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void clearsContextWhenAuthorizationFails() throws ServletException, IOException {
    authenticate();
    when(clientManager.authorize(any()))
        .thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void ignoresRequestsWithoutOAuth2Authentication() throws ServletException, IOException {
    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(clientManager);
  }

  @Test
  void tokenWithoutExpiryIsConsideredExpired() {
    OAuth2AccessToken withoutExpiry =
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", null, null);
    assertTrue(filter.hasTokenExpired(withoutExpiry));

    OAuth2AccessToken valid =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "token",
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(300));
    assertFalse(filter.hasTokenExpired(valid));
  }

  private void authenticate() {
    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getAuthorizedClientRegistrationId()).thenReturn("keycloak");
    when(token.getName()).thenReturn("gokhan");
    SecurityContextHolder.getContext().setAuthentication(token);
  }

  private OAuth2AuthorizedClient authorizedClient(Instant expiresAt) {
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "token", Instant.now().minusSeconds(60), expiresAt);
    when(client.getAccessToken()).thenReturn(accessToken);
    return client;
  }
}
