package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class SsoLogoutSuccessHandlerTests {

  @Test
  void redirectsToDefaultTargetUrlForNonOidcAuthentication() throws ServletException, IOException {
    SsoLogoutSuccessHandler handler =
        new SsoLogoutSuccessHandler(mock(ClientRegistrationRepository.class), new OAuth2Properties());
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    Authentication authentication = mock(Authentication.class);
    when(authentication.getName()).thenReturn("gokhan");

    handler.onLogoutSuccess(request, response, authentication);

    assertEquals("/", response.getRedirectedUrl());
  }

  /**
   * The provider endpoints are configured explicitly (no issuer-uri discovery), so the
   * registration carries no end_session_endpoint metadata. The handler must derive the
   * Keycloak logout endpoint from the browser-facing authorization-uri instead of falling
   * back to "/" (which the resource-server chain rejects with 401).
   */
  @Test
  void redirectsToKeycloakEndSessionEndpointDerivedFromAuthorizationUri()
      throws ServletException, IOException {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("camunda-identity-service")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
            .authorizationUri("http://rehearsal.test:8080/realms/dsync/protocol/openid-connect/auth")
            .tokenUri("http://keycloak:8080/realms/dsync/protocol/openid-connect/token")
            .build();
    ClientRegistrationRepository repository = mock(ClientRegistrationRepository.class);
    when(repository.findByRegistrationId("keycloak")).thenReturn(registration);

    OidcIdToken idToken =
        new OidcIdToken(
            "the-id-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("sub", "user-uuid", "email", "gokhan.demir@pia-team.com"));
    OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken, "email");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(oidcUser, List.of(), "keycloak");

    OAuth2Properties properties = new OAuth2Properties();
    properties.getSsoLogout().setPostLogoutRedirectUri("{baseUrl}/app/");
    SsoLogoutSuccessHandler handler = new SsoLogoutSuccessHandler(repository, properties);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(8091);
    request.setContextPath("/cadenzaflow/v1");
    request.setRequestURI("/cadenzaflow/v1/logout");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onLogoutSuccess(request, response, authentication);

    String redirect = response.getRedirectedUrl();
    assertNotNull(redirect);
    assertTrue(
        redirect.startsWith(
            "http://rehearsal.test:8080/realms/dsync/protocol/openid-connect/logout?"),
        "expected Keycloak end-session endpoint, got: " + redirect);
    assertTrue(redirect.contains("id_token_hint=the-id-token"), redirect);
    assertTrue(redirect.contains("client_id=camunda-identity-service"), redirect);
    assertTrue(
        redirect.contains(
            "post_logout_redirect_uri=http://localhost:8091/cadenzaflow/v1/app/"),
        redirect);
    // No shared callback: no end-session state, today's request unchanged.
    assertFalse(redirect.contains("state="), redirect);
  }

  /**
   * Entra ID's authorization endpoint ends with {@code /authorize} (not Keycloak's
   * {@code /auth}); its end-session endpoint is the {@code /logout} sibling.
   */
  @Test
  void redirectsToEntraEndSessionEndpointDerivedFromAuthorizeUri()
      throws ServletException, IOException {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("azure")
            .clientId("entra-client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
            .authorizationUri(
                "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/authorize")
            .tokenUri("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token")
            .build();
    ClientRegistrationRepository repository = mock(ClientRegistrationRepository.class);
    when(repository.findByRegistrationId("azure")).thenReturn(registration);

    OidcIdToken idToken =
        new OidcIdToken(
            "entra-id-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("sub", "user-uuid", "email", "gokhan.demir@pia-team.com"));
    OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken, "email");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(oidcUser, List.of(), "azure");

    OAuth2Properties properties = new OAuth2Properties();
    properties.getSsoLogout().setPostLogoutRedirectUri("{baseUrl}/app/");
    SsoLogoutSuccessHandler handler = new SsoLogoutSuccessHandler(repository, properties);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(8091);
    request.setContextPath("/cadenzaflow/v1");
    request.setRequestURI("/cadenzaflow/v1/logout");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onLogoutSuccess(request, response, authentication);

    String redirect = response.getRedirectedUrl();
    assertNotNull(redirect);
    assertTrue(
        redirect.startsWith(
            "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/logout?"),
        "expected Entra end-session endpoint, got: " + redirect);
    assertTrue(redirect.contains("id_token_hint=entra-id-token"), redirect);
    assertTrue(redirect.contains("client_id=entra-client"), redirect);
    assertTrue(
        redirect.contains(
            "post_logout_redirect_uri=http://localhost:8091/cadenzaflow/v1/app/"),
        redirect);
  }

  /**
   * With the shared callback configured, {@code post_logout_redirect_uri} is that URL
   * exactly as registered (Entra matches it verbatim, so no query), and the way back into
   * Cockpit - the path of the configured post-logout URI - rides in the end-session
   * {@code state} as {@code cf.so:<path>}.
   */
  @Test
  void sharedCallbackIsTheBareSignOutReturn() throws ServletException, IOException {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("oidc")
            .clientId("entra-client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
            .authorizationUri(
                "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/authorize")
            .tokenUri("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token")
            .build();
    ClientRegistrationRepository repository = mock(ClientRegistrationRepository.class);
    when(repository.findByRegistrationId("oidc")).thenReturn(registration);

    OidcIdToken idToken =
        new OidcIdToken(
            "entra-id-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("sub", "user-uuid", "email", "gokhan.demir@pia-team.com"));
    OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken, "email");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(oidcUser, List.of(), "oidc");

    OAuth2Properties properties = new OAuth2Properties();
    properties.getSsoLogout().setPostLogoutRedirectUri("{baseUrl}/app/");
    SsoLogoutSuccessHandler handler = new SsoLogoutSuccessHandler(
        repository, properties, "https://ui.example.com/auth/callback");

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("https");
    request.setServerName("ui.example.com");
    request.setServerPort(443);
    request.setContextPath("/cadenzaflow/v1");
    request.setRequestURI("/cadenzaflow/v1/logout");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onLogoutSuccess(request, response, authentication);

    String redirect = response.getRedirectedUrl();
    assertNotNull(redirect);
    assertTrue(
        redirect.startsWith(
            "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/logout?"),
        "expected Entra end-session endpoint, got: " + redirect);
    assertTrue(
        redirect.contains(
            "&post_logout_redirect_uri=https://ui.example.com/auth/callback&state="),
        redirect);
    assertTrue(redirect.endsWith("&state=cf.so:/cadenzaflow/v1/app/"), redirect);
    assertFalse(redirect.contains("callback?"), redirect);
  }

  /**
   * CF-SHARED-CALLBACK-3: a session opened by POST /app/login-with-token signs out the same
   * way, with the ID token the page sent at login as the hint.
   */
  @Test
  void aBearerSessionSignsOutWithItsIdTokenAsTheHint() throws ServletException, IOException {
    String redirect = bearerLogout(new BearerSessionDetails("page-id-token", Instant.now()));

    assertTrue(
        redirect.startsWith("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/logout?"
            + "id_token_hint=page-id-token&client_id=entra-client&"),
        redirect);
    assertTrue(
        redirect.endsWith("post_logout_redirect_uri=https://ui.example.com/auth/callback"
            + "&state=cf.so:/cadenzaflow/v1/app/"),
        redirect);
  }

  /** Without an ID token from the page, the end-session request goes without a hint. */
  @Test
  void aBearerSessionWithoutIdTokenSignsOutWithoutHint() throws ServletException, IOException {
    String redirect = bearerLogout(new BearerSessionDetails(null, Instant.now()));

    assertTrue(
        redirect.startsWith("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/logout?"
            + "client_id=entra-client&post_logout_redirect_uri="),
        redirect);
    assertFalse(redirect.contains("id_token_hint"), redirect);
  }

  private static String bearerLogout(BearerSessionDetails details)
      throws ServletException, IOException {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("oidc")
            .clientId("entra-client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
            .authorizationUri(
                "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/authorize")
            .tokenUri("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token")
            .build();
    Jwt jwt = Jwt.withTokenValue("access-token")
        .header("alg", "RS256")
        .claim("email", "gokhan.demir@pia-team.com")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
    BearerSessionAuthentication authentication = new BearerSessionAuthentication(
        jwt, "gokhan.demir@pia-team.com", List.of(), details);

    OAuth2Properties properties = new OAuth2Properties();
    properties.getSsoLogout().setPostLogoutRedirectUri("{baseUrl}/app/");
    SsoLogoutSuccessHandler handler = new SsoLogoutSuccessHandler(
        new InMemoryClientRegistrationRepository(registration), properties,
        "https://ui.example.com/auth/callback");

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("https");
    request.setServerName("ui.example.com");
    request.setServerPort(443);
    request.setContextPath("/cadenzaflow/v1");
    request.setRequestURI("/cadenzaflow/v1/logout");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onLogoutSuccess(request, response, authentication);

    String redirect = response.getRedirectedUrl();
    assertNotNull(redirect);
    return redirect;
  }
}
