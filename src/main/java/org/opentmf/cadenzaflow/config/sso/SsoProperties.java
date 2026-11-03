package org.opentmf.cadenzaflow.config.sso;

import java.net.URI;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webapp single-sign-on settings that are not part of the engine's own
 * {@code cadenzaflow.bpm.oauth2} block.
 *
 * @author Cezmi Aslan
 */
@ConfigurationProperties(SsoProperties.PREFIX)
public class SsoProperties {

  public static final String PREFIX = "cadenzaflow.sso";

  /**
   * Absolute URL of the browser callback page the UIs share, e.g.
   * {@code https://ui.example.com/auth/callback}. When it is set it is the sign-out return
   * (with the end-session state {@code cf.so:<path>}). Empty (the default) keeps today's
   * sign-out return.
   */
  private String sharedCallbackUrl = "";

  /**
   * Also SIGN IN through {@link #sharedCallbackUrl}: it then replaces the registration's
   * redirect URI and the state gets the {@code cf.} prefix the shared page routes on.
   * Default {@code false}, which is the only shape Entra allows: Entra binds a redirect URI
   * to one platform type, and Cockpit redeems its code server-side (Web) while the UIs
   * redeem theirs in the browser (SPA), so one URI cannot serve both sign-ins there. Set it
   * only where the provider has no platform types, e.g. Keycloak.
   */
  private boolean sharedCallbackSignIn = false;

  public String getSharedCallbackUrl() {
    return sharedCallbackUrl;
  }

  public void setSharedCallbackUrl(String sharedCallbackUrl) {
    this.sharedCallbackUrl = sharedCallbackUrl;
  }

  public boolean isSharedCallbackSignIn() {
    return sharedCallbackSignIn;
  }

  public void setSharedCallbackSignIn(boolean sharedCallbackSignIn) {
    this.sharedCallbackSignIn = sharedCallbackSignIn;
  }

  /**
   * The shared callback URL for the sign-in, if sign-in through it is switched on.
   *
   * @throws IllegalStateException when sign-in is switched on without a valid URL
   */
  public Optional<String> sharedCallbackSignInUrl() {
    if (!sharedCallbackSignIn) {
      return Optional.empty();
    }
    Optional<String> url = sharedCallback();
    if (url.isEmpty()) {
      throw new IllegalStateException(
          PREFIX + ".shared-callback-sign-in is true but " + PREFIX
              + ".shared-callback-url is empty");
    }
    return url;
  }

  /**
   * The shared callback URL, if one is configured.
   *
   * @throws IllegalStateException when the value is set but is not an absolute http(s)
   *     URL - a relative or mistyped value would only fail later at the provider, as a
   *     redirect-URI mismatch the user cannot act on
   */
  public Optional<String> sharedCallback() {
    if (sharedCallbackUrl == null || sharedCallbackUrl.isBlank()) {
      return Optional.empty();
    }
    String value = sharedCallbackUrl.trim();
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw invalid(value, e);
    }
    String scheme = uri.getScheme();
    if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
        || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
      throw invalid(value, null);
    }
    return Optional.of(value);
  }

  private static IllegalStateException invalid(String value, Throwable cause) {
    return new IllegalStateException(
        PREFIX + ".shared-callback-url must be an absolute http(s) URL without query or"
            + " fragment: '" + value + "'", cause);
  }
}
