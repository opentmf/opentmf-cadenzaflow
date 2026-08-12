/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opentmf.cadenzaflow.config.sso;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link OidcClientInitiatedLogoutSuccessHandler} that also works without OIDC discovery.
 *
 * <p>The parent handler resolves Keycloak's end-session endpoint from the client
 * registration's provider metadata, which is only populated when the registration is built
 * from {@code issuer-uri} discovery. This application configures the provider endpoints
 * explicitly (split-DNS deployments reach Keycloak on an internal URL whose discovery
 * document advertises the public issuer, so discovery cannot be used) - leaving the
 * metadata empty and the parent silently redirecting to "/", which the resource-server
 * chain rejects with 401. This handler instead derives the browser-facing end-session URL
 * from the configured {@code authorization-uri} (same host the browser already used to log
 * in) and falls back to the parent's behavior for non-OIDC authentications.
 *
 * @author Abdullah Beker
 */
public class SsoLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(SsoLogoutSuccessHandler.class);
  private static final String KEYCLOAK_AUTH_SUFFIX = "/auth";
  private static final String ENTRA_AUTH_SUFFIX = "/authorize";
  private static final String LOGOUT_ENDPOINT_SUFFIX = "/logout";

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final String postLogoutRedirectUri;

  public SsoLogoutSuccessHandler(
      ClientRegistrationRepository clientRegistrationRepository,
      OAuth2Properties oAuth2Properties) {
    super(clientRegistrationRepository);
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.postLogoutRedirectUri = oAuth2Properties.getSsoLogout().getPostLogoutRedirectUri();
    this.setPostLogoutRedirectUri(this.postLogoutRedirectUri);
  }

  @Override
  public void onLogoutSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    // authentication is null when /logout is hit without an authenticated session
    log.debug(
        "Initiating SSO logout for '{}'",
        authentication != null ? authentication.getName() : "<unauthenticated>");
    super.onLogoutSuccess(request, response, authentication);
  }

  @Override
  protected String determineTargetUrl(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    if (authentication instanceof OAuth2AuthenticationToken token
        && token.getPrincipal() instanceof OidcUser oidcUser) {
      ClientRegistration registration =
          clientRegistrationRepository.findByRegistrationId(
              token.getAuthorizedClientRegistrationId());
      String endSessionEndpoint = endSessionEndpoint(registration);
      if (endSessionEndpoint != null) {
        return UriComponentsBuilder.fromUriString(endSessionEndpoint)
            .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
            .queryParam("client_id", registration.getClientId())
            .queryParam("post_logout_redirect_uri", resolvePostLogoutRedirectUri(request))
            .encode(StandardCharsets.UTF_8)
            .build()
            .toUriString();
      }
    }
    return super.determineTargetUrl(request, response, authentication);
  }

  private static String endSessionEndpoint(ClientRegistration registration) {
    if (registration == null) {
      return null;
    }
    String authorizationUri = registration.getProviderDetails().getAuthorizationUri();
    if (authorizationUri == null) {
      return null;
    }
    String suffix = authorizationEndpointSuffix(authorizationUri);
    if (suffix == null) {
      return null;
    }
    return authorizationUri.substring(0, authorizationUri.length() - suffix.length())
        + LOGOUT_ENDPOINT_SUFFIX;
  }

  /**
   * The provider's authorization-endpoint suffix, or {@code null} when the URI belongs to
   * neither provider we know how to derive an end-session endpoint for.
   *
   * <p>Keycloak: {@code .../protocol/openid-connect/auth} -> {@code .../logout}<br>
   * Entra ID: {@code .../oauth2/v2.0/authorize} -> {@code .../oauth2/v2.0/logout}</p>
   */
  private static String authorizationEndpointSuffix(String authorizationUri) {
    if (authorizationUri.endsWith(ENTRA_AUTH_SUFFIX)) {
      return ENTRA_AUTH_SUFFIX;
    }
    if (authorizationUri.endsWith(KEYCLOAK_AUTH_SUFFIX)) {
      return KEYCLOAK_AUTH_SUFFIX;
    }
    return null;
  }

  private String resolvePostLogoutRedirectUri(HttpServletRequest request) {
    String baseUrl =
        UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
            .replacePath(request.getContextPath())
            .replaceQuery(null)
            .fragment(null)
            .build()
            .toUriString();
    return UriComponentsBuilder.fromUriString(postLogoutRedirectUri)
        .buildAndExpand(Map.of("baseUrl", baseUrl))
        .toUriString();
  }
}
