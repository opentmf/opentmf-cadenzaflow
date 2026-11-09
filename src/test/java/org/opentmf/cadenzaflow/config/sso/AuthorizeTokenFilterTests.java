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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

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

  /**
   * CF-SHARED-CALLBACK-3 (D-455): a bearer session is an ordinary webapp session, capped
   * at bearer-session-max from its opening; the access token's own expiry does not end it.
   */
  @Test
  void endsABearerSessionOlderThanItsMaximum() throws ServletException, IOException {
    MockHttpSession session = new MockHttpSession();
    request.setSession(session);
    // 8 h + 1 s: the filter's clock is the engine's ClockUtil (a Date, millisecond
    // precision); an exact 8 h would race Instant.now()'s nanoseconds.
    authenticateBearer(Instant.now().plus(Duration.ofHours(8)).minusSeconds(1),
        Instant.now().minus(Duration.ofHours(8)).minusSeconds(1));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertTrue(session.isInvalid());
    verifyNoInteractions(clientManager);
  }

  @Test
  void keepsABearerSessionAfterItsTokenExpiredWithinItsMaximum()
      throws ServletException, IOException {
    MockHttpSession session = new MockHttpSession();
    request.setSession(session);
    authenticateBearer(Instant.now().minusSeconds(60), Instant.now().minus(Duration.ofHours(1)));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    assertFalse(session.isInvalid());
    verifyNoInteractions(clientManager);
  }

  @Test
  void theMaximumIsConfigurable() throws ServletException, IOException {
    AuthorizeTokenFilter shortLived =
        new AuthorizeTokenFilter(clientManager, Duration.ofMinutes(5));
    authenticateBearer(Instant.now().plusSeconds(300), Instant.now().minusSeconds(301));

    shortLived.doFilterInternal(request, response, chain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  /** A bearer session that lost its details cannot be dated, so it ends (fail closed). */
  @Test
  void endsABearerSessionWithoutDetails() throws ServletException, IOException {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("email", "x@y.test")
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new BearerSessionAuthentication(jwt, "x@y.test", List.of(), null));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  private void authenticateBearer(Instant tokenExpiresAt, Instant openedAt) {
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("email", "gokhan@example.test")
        .issuedAt(tokenExpiresAt.minusSeconds(600))
        .expiresAt(tokenExpiresAt)
        .build();
    SecurityContextHolder.getContext().setAuthentication(new BearerSessionAuthentication(
        jwt, "gokhan@example.test", List.of(), new BearerSessionDetails(null, openedAt)));
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
