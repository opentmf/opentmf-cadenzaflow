package org.opentmf.cadenzaflow.config.sso;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

  /** Default of {@code bearer-session-max}. */
  public static final Duration DEFAULT_BEARER_SESSION_MAX = Duration.ofHours(8);

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

  /**
   * Absolute URL of the shared page's Cockpit sign-in route (CF-SHARED-CALLBACK-3). When it
   * is set, an unauthenticated webapp request is redirected there with
   * {@code returnTo=<requested path>} instead of starting the server-side OAuth2 login. The
   * page signs the user in in the browser and posts the access token to
   * {@code POST /app/login-with-token}, an endpoint that exists only while this is set.
   * Empty (the default) keeps the server-side login.
   */
  private String loginPageUrl = "";

  /**
   * The most a session opened by {@code POST /app/login-with-token} may last, however
   * active it is. The webapp's idle timeout applies as for any session; the token's own
   * expiry does not end it.
   */
  private Duration bearerSessionMax = DEFAULT_BEARER_SESSION_MAX;

  /**
   * The audiences an {@code X-ID-Token} of {@code POST /app/login-with-token} may carry
   * (CF-ID-TOKEN-AUDIENCES-1): an ID token whose {@code aud} names any of them passes. An ID
   * token is issued to the client that signed the user in, which is the shared page's
   * console client. On Entra that is usually the same app as the webapp registration; on a
   * Keycloak stack it is a client of its own (e.g. {@code dnms-config-ui}). Empty (the
   * default): per issuer, the API audiences {@code /engine-rest} accepts from it plus the
   * webapp registration's {@code client-id}, and no audience check for an issuer that
   * declares none, as on {@code /engine-rest}. Set, the list REPLACES that default on every
   * issuer, for a deployment that wants it narrow.
   */
  private List<String> idTokenAudiences = new ArrayList<>();

  /** {@code POST /app/login-with-token} settings. */
  private final BearerLogin bearerLogin = new BearerLogin();

  public static class BearerLogin {

    /**
     * Only a delegated USER token may open a session; a client-credentials token of a
     * service or worker, which {@code /engine-rest} also trusts, is refused (401). Default
     * {@code true}. See {@link UserTokenPolicy}.
     */
    private boolean userTokenOnly = true;

    public boolean isUserTokenOnly() {
      return userTokenOnly;
    }

    public void setUserTokenOnly(boolean userTokenOnly) {
      this.userTokenOnly = userTokenOnly;
    }

    /**
     * The token claim that names the session's engine user
     * (OPENTMF-CADENZAFLOW-BEARER-LOGIN-USER-CLAIM-1), for projects on a directory provider, e.g.
     * {@code email} where the identity provider keys users on their mail address while
     * {@code /engine-rest} maps tokens by {@code oid}. Empty (the default) keeps the
     * principal {@code /engine-rest} derives with the issuer's {@code user-claim}. Token
     * validation and the authorities are not affected.
     */
    private String userClaim = "";

    public String getUserClaim() {
      return userClaim;
    }

    public void setUserClaim(String userClaim) {
      this.userClaim = userClaim;
    }

    /** The configured user claim, or empty for the issuer's. */
    public Optional<String> userClaimOverride() {
      return userClaim == null || userClaim.isBlank()
          ? Optional.empty()
          : Optional.of(userClaim.trim());
    }
  }

  public List<String> getIdTokenAudiences() {
    return idTokenAudiences;
  }

  public void setIdTokenAudiences(List<String> idTokenAudiences) {
    this.idTokenAudiences = idTokenAudiences;
  }

  /** The configured ID-token audiences without blank entries, or empty for the default. */
  public List<String> idTokenAudienceOverride() {
    return idTokenAudiences == null
        ? List.of()
        : idTokenAudiences.stream()
            .filter(audience -> audience != null && !audience.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
  }

  public BearerLogin getBearerLogin() {
    return bearerLogin;
  }

  public Duration getBearerSessionMax() {
    return bearerSessionMax;
  }

  public void setBearerSessionMax(Duration bearerSessionMax) {
    this.bearerSessionMax = bearerSessionMax;
  }

  public String getSharedCallbackUrl() {
    return sharedCallbackUrl;
  }

  public void setSharedCallbackUrl(String sharedCallbackUrl) {
    this.sharedCallbackUrl = sharedCallbackUrl;
  }

  public String getLoginPageUrl() {
    return loginPageUrl;
  }

  public void setLoginPageUrl(String loginPageUrl) {
    this.loginPageUrl = loginPageUrl;
  }

  /**
   * The shared page's Cockpit sign-in route, if one is configured.
   *
   * @throws IllegalStateException when the value is set but is not an absolute http(s) URL
   *     without query or fragment
   */
  public Optional<String> loginPage() {
    return absoluteUrl("login-page-url", loginPageUrl);
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
    return absoluteUrl("shared-callback-url", sharedCallbackUrl);
  }

  private static Optional<String> absoluteUrl(String key, String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String value = raw.trim();
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw invalid(key, value, e);
    }
    String scheme = uri.getScheme();
    if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
        || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
      throw invalid(key, value, null);
    }
    return Optional.of(value);
  }

  private static IllegalStateException invalid(String key, String value, Throwable cause) {
    return new IllegalStateException(
        PREFIX + "." + key + " must be an absolute http(s) URL without query or"
            + " fragment: '" + value + "'", cause);
  }
}
