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

import jakarta.annotation.Nullable;
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

  /**
   * Prefix of the end-session {@code state} with the shared callback: the shared page reads
   * the path after it, checks it against its allowlist and sends the browser there.
   */
  public static final String SIGN_OUT_STATE_PREFIX =
      SharedCallbackAuthorizationRequestCustomizer.STATE_PREFIX + "so:";

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final String postLogoutRedirectUri;
  private final String sharedCallbackUrl;

  public SsoLogoutSuccessHandler(
      ClientRegistrationRepository clientRegistrationRepository,
      OAuth2Properties oAuth2Properties) {
    this(clientRegistrationRepository, oAuth2Properties, null);
  }

  /**
   * @param sharedCallbackUrl the shared browser callback URL, or {@code null}. When it is
   *     set it is the {@code post_logout_redirect_uri} as it stands - no query, because
   *     Entra matches it exactly against the registered URI - and the way back into
   *     Cockpit (the path of the configured post-logout URI) travels in the end-session
   *     {@code state} as {@code cf.so:<path>}, which the provider hands back unchanged.
   */
  public SsoLogoutSuccessHandler(
      ClientRegistrationRepository clientRegistrationRepository,
      OAuth2Properties oAuth2Properties,
      @Nullable String sharedCallbackUrl) {
    super(clientRegistrationRepository);
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.postLogoutRedirectUri = oAuth2Properties.getSsoLogout().getPostLogoutRedirectUri();
    this.sharedCallbackUrl = sharedCallbackUrl;
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
      String target = endSessionUrl(
          clientRegistrationRepository.findByRegistrationId(
              token.getAuthorizedClientRegistrationId()),
          oidcUser.getIdToken().getTokenValue(),
          request);
      if (target != null) {
        return target;
      }
    }
    if (authentication instanceof BearerSessionAuthentication bearer) {
      // A session opened by POST /app/login-with-token (CF-SHARED-CALLBACK-3): the hint is
      // the ID token the page sent at login, if any. Without one the end-session request
      // goes without id_token_hint (the provider may then ask which account to sign out).
      BearerSessionDetails details = bearer.getDetails();
      String idTokenHint = details != null ? details.idToken() : null;
      String target = endSessionUrl(soleRegistration(), idTokenHint, request);
      if (target != null) {
        return target;
      }
    }
    return super.determineTargetUrl(request, response, authentication);
  }

  private String endSessionUrl(
      ClientRegistration registration, @Nullable String idTokenHint, HttpServletRequest request) {
    String endSessionEndpoint = endSessionEndpoint(registration);
    if (endSessionEndpoint == null) {
      return null;
    }
    UriComponentsBuilder target = UriComponentsBuilder.fromUriString(endSessionEndpoint);
    if (idTokenHint != null) {
      target.queryParam("id_token_hint", idTokenHint);
    }
    target.queryParam("client_id", registration.getClientId());
    if (sharedCallbackUrl != null) {
      target
          .queryParam("post_logout_redirect_uri", sharedCallbackUrl)
          .queryParam("state", SIGN_OUT_STATE_PREFIX + returnPath(request));
    } else {
      target.queryParam("post_logout_redirect_uri", resolvePostLogoutRedirectUri(request));
    }
    return target.encode(StandardCharsets.UTF_8).build().toUriString();
  }

  /** The configured registration when there is exactly one, else {@code null}. */
  private ClientRegistration soleRegistration() {
    ClientRegistration sole = null;
    if (clientRegistrationRepository instanceof Iterable<?> registrations) {
      for (Object registration : registrations) {
        if (sole != null) {
          return null;
        }
        sole = (ClientRegistration) registration;
      }
    }
    return sole;
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

  /** The path part of the resolved post-logout URI - where Cockpit's user goes back to. */
  private String returnPath(HttpServletRequest request) {
    String path = UriComponentsBuilder.fromUriString(resolvePostLogoutRedirectUri(request))
        .build()
        .getPath();
    return path == null || path.isEmpty() ? "/" : path;
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
