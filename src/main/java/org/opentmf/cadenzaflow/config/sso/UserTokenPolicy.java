package org.opentmf.cadenzaflow.config.sso;

import java.util.List;
import java.util.Optional;
import org.opentmf.security.config.CommonConfig;
import org.opentmf.security.config.ResolvedIssuer;
import org.opentmf.security.model.OpenTmfSecurityProperties;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Only a DELEGATED USER token may open a webapp session by {@code POST /app/login-with-token}
 * ({@code cadenzaflow.sso.bearer-login.user-token-only}, default {@code true}; D-457).
 * {@code /engine-rest} also trusts client-credentials tokens of services and workers; they
 * are refused here:
 *
 * <ul>
 *   <li>the issuer's configured user claim must be present and non-blank (resolved exactly
 *       as openid-rbac-security resolves it, per issuer);</li>
 *   <li>Entra: {@code idtyp=app} is an application token, and a token of an Entra issuer
 *       without {@code scp} carries no delegated scope;</li>
 *   <li>Keycloak: a principal or {@code preferred_username} of {@code service-account-*} is
 *       a client's service account.</li>
 * </ul>
 *
 * @author Cezmi Aslan
 */
public class UserTokenPolicy {

  private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

  private final List<ResolvedIssuer> issuers;

  public UserTokenPolicy(OpenTmfSecurityProperties properties) {
    this(CommonConfig.resolveIssuers(properties));
  }

  UserTokenPolicy(List<ResolvedIssuer> issuers) {
    this.issuers = issuers;
  }

  /**
   * Why this token may not open a session, or empty when it may.
   *
   * @param name the principal name the resource server derived from the token
   */
  public Optional<String> refusal(Jwt jwt, String name) {
    String userClaim = userClaim(jwt);
    if (userClaim == null) {
      return Optional.of("no trusted issuer configuration names a user claim for '"
          + jwt.getClaimAsString("iss") + "'");
    }
    String user = jwt.getClaimAsString(userClaim);
    if (user == null || user.isBlank()) {
      return Optional.of("the token has no '" + userClaim + "' (user) claim");
    }
    if ("app".equalsIgnoreCase(jwt.getClaimAsString("idtyp"))) {
      return Optional.of("an application token (idtyp=app), not a delegated user token");
    }
    if (isEntra(jwt.getClaimAsString("iss")) && !jwt.hasClaim("scp")) {
      return Optional.of("an Entra token without 'scp', not a delegated user token");
    }
    if (isServiceAccount(name) || isServiceAccount(jwt.getClaimAsString("preferred_username"))
        || isServiceAccount(user)) {
      return Optional.of("a service-account token, not a user token");
    }
    return Optional.empty();
  }

  private String userClaim(Jwt jwt) {
    String iss = jwt.getClaimAsString("iss");
    for (ResolvedIssuer issuer : issuers) {
      if (issuer.issuer() != null && issuer.issuer().equals(iss)) {
        return issuer.userClaim();
      }
    }
    // Single-issuer mode pins no issuer: its one entry applies to every token it accepts.
    return issuers.size() == 1 && !issuers.get(0).pinsIssuer()
        ? issuers.get(0).userClaim()
        : null;
  }

  private static boolean isEntra(String iss) {
    return iss != null && (iss.startsWith("https://login.microsoftonline.com/")
        || iss.startsWith("https://sts.windows.net/"));
  }

  private static boolean isServiceAccount(String value) {
    return value != null && value.toLowerCase().startsWith(SERVICE_ACCOUNT_PREFIX);
  }
}
