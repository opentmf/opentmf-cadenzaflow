package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.rest.security.auth.AuthenticationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class OAuth2AuthenticationProviderTests {

  private final OAuth2AuthenticationProvider provider = new OAuth2AuthenticationProvider();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final ProcessEngine engine = mock(ProcessEngine.class);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void unauthenticatedWithoutSecurityContext() {
    AuthenticationResult result = provider.extractAuthenticatedUser(request, engine);
    assertFalse(result.isAuthenticated());
  }

  @Test
  void unauthenticatedForNonOAuth2Authentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("gokhan", "n/a"));

    AuthenticationResult result = provider.extractAuthenticatedUser(request, engine);
    assertFalse(result.isAuthenticated());
  }

  @Test
  void unauthenticatedForOAuth2TokenWithoutUserName() {
    authenticate("");

    AuthenticationResult result = provider.extractAuthenticatedUser(request, engine);
    assertFalse(result.isAuthenticated());
  }

  @Test
  void authenticatedUserIdIsTakenFromOAuth2Token() {
    authenticate("gokhan");

    AuthenticationResult result = provider.extractAuthenticatedUser(request, engine);
    assertTrue(result.isAuthenticated());
    assertEquals("gokhan", result.getAuthenticatedUser());
  }

  /** CF-SHARED-CALLBACK-3: a session opened by POST /app/login-with-token. */
  @Test
  void authenticatedUserIdIsTakenFromABearerSession() {
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("email", "gokhan@example.test")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
    SecurityContextHolder.getContext().setAuthentication(new BearerSessionAuthentication(
        jwt, "gokhan@example.test", List.of(), new BearerSessionDetails(null, Instant.now())));

    AuthenticationResult result = provider.extractAuthenticatedUser(request, engine);
    assertTrue(result.isAuthenticated());
    assertEquals("gokhan@example.test", result.getAuthenticatedUser());
  }

  private void authenticate(String name) {
    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getName()).thenReturn(name);
    SecurityContextHolder.getContext().setAuthentication(token);
  }
}
