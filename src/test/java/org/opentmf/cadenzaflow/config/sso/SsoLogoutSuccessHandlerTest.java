package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class SsoLogoutSuccessHandlerTest {

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
  }
}
