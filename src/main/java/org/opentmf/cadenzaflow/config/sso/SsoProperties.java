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
   * {@code https://ui.example.com/auth/callback}. When it is set it replaces the
   * registration's redirect URI for the sign-in and becomes the sign-out return, so one
   * redirect URI per environment serves Cockpit and the UIs. The page bounces states that
   * start with {@code cf.} to this application's {@code /login/oauth2/code/<id>}. Empty
   * (the default) keeps Spring's template redirect URI and today's sign-out return.
   */
  private String sharedCallbackUrl = "";

  public String getSharedCallbackUrl() {
    return sharedCallbackUrl;
  }

  public void setSharedCallbackUrl(String sharedCallbackUrl) {
    this.sharedCallbackUrl = sharedCallbackUrl;
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
