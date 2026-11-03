package org.opentmf.cadenzaflow.config.sso;

import jakarta.annotation.Nullable;
import java.util.Base64;
import java.util.function.Consumer;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Shapes every webapp authorization request.
 *
 * <ul>
 *   <li><b>PKCE</b>, always and first: an authorization code is useless without this
 *       session's verifier. Keycloak and Entra both accept PKCE from a confidential
 *       client.</li>
 *   <li>Only when signing in through the shared callback
 *       ({@code cadenzaflow.sso.shared-callback-sign-in}):
 *       <ul>
 *         <li><b>redirect URI</b> = the shared callback URL. The token exchange sends the
 *             authorization request's redirect URI, so both legs carry the same value,
 *             which is what the provider checks.</li>
 *         <li><b>{@code state} = {@code cf.} + a fresh secure random key</b>: the prefix
 *             is how the shared page tells a Cockpit bounce from an SPA one. Spring keeps
 *             the saved request under the whole state, so nothing else changes on the way
 *             back.</li>
 *       </ul>
 *       Otherwise the registration's own redirect URI and Spring's state stand.</li>
 * </ul>
 *
 * @author Cezmi Aslan
 */
public class SharedCallbackAuthorizationRequestCustomizer
    implements Consumer<OAuth2AuthorizationRequest.Builder> {

  /** Prefix of every state this application issues; the shared page routes on it. */
  public static final String STATE_PREFIX = "cf.";

  // The same generator Spring's resolver uses for its default state.
  private static final StringKeyGenerator STATE_GENERATOR =
      new Base64StringKeyGenerator(Base64.getUrlEncoder());

  private final Consumer<OAuth2AuthorizationRequest.Builder> pkce =
      OAuth2AuthorizationRequestCustomizers.withPkce();
  private final String signInCallbackUrl;

  /**
   * @param signInCallbackUrl the shared callback URL to sign in through, or {@code null}
   *     to keep the registration's own redirect URI and Spring's state
   */
  public SharedCallbackAuthorizationRequestCustomizer(@Nullable String signInCallbackUrl) {
    this.signInCallbackUrl = signInCallbackUrl;
  }

  @Override
  public void accept(OAuth2AuthorizationRequest.Builder builder) {
    pkce.accept(builder);
    if (signInCallbackUrl != null) {
      builder.redirectUri(signInCallbackUrl);
      builder.state(STATE_PREFIX + STATE_GENERATOR.generateKey());
    }
  }
}
