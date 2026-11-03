package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Transient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class LoginWithTokenFilterTests {

  private final JwtDecoder idTokenDecoder = mock(JwtDecoder.class);
  private final UserTokenPolicy userTokenPolicy = mock(UserTokenPolicy.class);
  private final LoginWithTokenFilter filter =
      new LoginWithTokenFilter(idTokenDecoder, userTokenPolicy);
  private final MockHttpServletRequest request = new MockHttpServletRequest("POST",
      "/cadenzaflow/v1" + LoginWithTokenFilter.PATH);
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final FilterChain chain = mock(FilterChain.class);

  LoginWithTokenFilterTests() {
    request.setContextPath("/cadenzaflow/v1");
    request.setServerName("ui.example.test");
  }

  @BeforeEach
  void everyTokenIsAUserToken() {
    when(userTokenPolicy.refusal(any(), any())).thenReturn(Optional.empty());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aValidatedTokenOpensANewSessionHoldingIt() throws Exception {
    MockHttpSession previous = new MockHttpSession();
    request.setSession(previous);
    request.addHeader("Origin", "https://ui.example.test");
    request.addHeader("Sec-Fetch-Site", "same-origin");
    JwtAuthenticationToken token = authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(204, response.getStatus());
    assertEquals("/cadenzaflow/v1/app/cockpit/", response.getHeader("Location"));
    assertTrue(previous.isInvalid(), "the browser's earlier session must not be reused");
    HttpSession session = request.getSession(false);
    assertNotNull(session);
    assertNotSame(previous, session);
    SecurityContext stored = (SecurityContext) session.getAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertNotNull(stored, "the session must hold the login");
    BearerSessionAuthentication kept =
        assertInstanceOf(BearerSessionAuthentication.class, stored.getAuthentication());
    assertEquals(token.getName(), kept.getName());
    assertEquals(token.getAuthorities(), kept.getAuthorities());
    assertSame(token.getToken(), kept.getToken());
    assertTrue(kept.isAuthenticated());
    BearerSessionDetails details = kept.getDetails();
    assertNull(details.idToken());
    assertFalse(details.openedAt().isAfter(Instant.now()));
    verifyNoInteractions(chain, idTokenDecoder);
  }

  @Test
  void aValidIdTokenIsKeptAsTheLogoutHint() throws Exception {
    request.addHeader(LoginWithTokenFilter.ID_TOKEN_HEADER, "the-id-token");
    when(idTokenDecoder.decode("the-id-token")).thenReturn(jwt());
    authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(204, response.getStatus());
    verify(idTokenDecoder).decode("the-id-token");
    assertEquals("the-id-token", storedAuthentication().getDetails().idToken());
  }

  /**
   * The gate-2 defect of af3737c: Spring's JwtAuthenticationToken is @Transient, and the
   * session repository silently drops a transient authentication. What the filter stores
   * must not be one - a refactor back to storing the JwtAuthenticationToken goes red here.
   */
  @Test
  void theStoredAuthenticationIsNotTransient() throws Exception {
    authenticate();

    filter.doFilter(request, response, chain);

    Class<?> stored = storedAuthentication().getClass();
    assertNull(AnnotationUtils.findAnnotation(stored, Transient.class),
        stored + " is @Transient and would never reach the session");
  }

  private BearerSessionAuthentication storedAuthentication() {
    HttpSession session = request.getSession(false);
    assertNotNull(session);
    SecurityContext stored = (SecurityContext) session.getAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertNotNull(stored, "the session must hold the login");
    return assertInstanceOf(BearerSessionAuthentication.class, stored.getAuthentication());
  }

  @Test
  void anInvalidIdTokenRefusesTheLoginAndStoresNothing() throws Exception {
    request.addHeader(LoginWithTokenFilter.ID_TOKEN_HEADER, "forged");
    when(idTokenDecoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));
    authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertNull(request.getSession(false));
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  /** D-457: a client-credentials token, which /engine-rest accepts, opens no session. */
  @Test
  void aNonUserTokenIsRefusedAndStoresNothing() throws Exception {
    when(userTokenPolicy.refusal(any(), any()))
        .thenReturn(Optional.of("a service-account token, not a user token"));
    authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertNull(request.getSession(false));
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void withUserTokenOnlyOffEveryValidatedTokenIsAccepted() throws Exception {
    LoginWithTokenFilter permissive = new LoginWithTokenFilter(idTokenDecoder, null);
    authenticate();

    permissive.doFilter(request, response, chain);

    assertEquals(204, response.getStatus());
  }

  @Test
  void anotherOriginIsRefusedAndStoresNothing() throws Exception {
    request.addHeader("Origin", "https://evil.example");
    authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(403, response.getStatus());
    assertNull(request.getSession(false));
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void aCrossSiteFetchIsRefused() throws Exception {
    request.addHeader("Sec-Fetch-Site", "cross-site");
    authenticate();

    filter.doFilter(request, response, chain);

    assertEquals(403, response.getStatus());
    assertNull(request.getSession(false));
  }

  @Test
  void withoutABearerAuthenticationNothingHappens() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("gokhan", "n/a"));

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertNull(request.getSession(false));
  }

  @Test
  void sameOriginReadsBothBrowserSignals() {
    MockHttpServletRequest plain = new MockHttpServletRequest();
    plain.setServerName("ui.example.test");
    assertTrue(LoginWithTokenFilter.sameOrigin(plain), "a non-browser caller sends neither");

    plain.addHeader("Origin", "https://UI.example.test:443");
    assertTrue(LoginWithTokenFilter.sameOrigin(plain));

    MockHttpServletRequest sameSite = new MockHttpServletRequest();
    sameSite.setServerName("ui.example.test");
    sameSite.addHeader("Sec-Fetch-Site", "same-site");
    assertFalse(LoginWithTokenFilter.sameOrigin(sameSite));

    MockHttpServletRequest garbled = new MockHttpServletRequest();
    garbled.setServerName("ui.example.test");
    garbled.addHeader("Origin", "not a uri");
    assertFalse(LoginWithTokenFilter.sameOrigin(garbled));

    MockHttpServletRequest opaque = new MockHttpServletRequest();
    opaque.setServerName("ui.example.test");
    opaque.addHeader("Origin", "null");
    assertFalse(LoginWithTokenFilter.sameOrigin(opaque));
  }

  private JwtAuthenticationToken authenticate() {
    JwtAuthenticationToken token =
        new JwtAuthenticationToken(jwt(), List.of(), "gokhan@example.test");
    SecurityContextHolder.getContext().setAuthentication(token);
    return token;
  }

  private static Jwt jwt() {
    return Jwt.withTokenValue("access-token")
        .header("alg", "RS256")
        .claim("email", "gokhan@example.test")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
