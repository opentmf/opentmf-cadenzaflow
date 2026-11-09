package org.opentmf.cadenzaflow.config.sso;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.opentmf.security.config.CommonConfig;
import org.opentmf.security.config.ResolvedIssuer;
import org.opentmf.security.jwks.IssuerKeys;
import org.opentmf.security.jwks.KeyOutageAwareJwtDecoder;
import org.opentmf.security.jwks.TrustedIssuerKeys;
import org.opentmf.security.jwt.CompositeJwtDecoder;
import org.opentmf.security.model.OpenTmfSecurityProperties;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The decoder for the {@code X-ID-Token} of {@code POST /app/login-with-token}
 * (CF-ID-TOKEN-AUDIENCE-CLIENT-ID-1, CF-ID-TOKEN-AUDIENCES-1).
 *
 * <p>An ID token is issued to the OIDC client that signed the user in, so its {@code aud} is
 * that client's id, which need not be an API audience {@code /engine-rest} accepts. This
 * decoder trusts exactly the issuers {@code /engine-rest} trusts, over the same cached keys,
 * with the same signature, expiry and issuer checks. Only its audience rule differs:</p>
 *
 * <ul>
 *   <li>{@link #byDefault}: per issuer, the API audiences {@code /engine-rest} accepts from it
 *       PLUS the webapp registration's client id. An issuer that declares no audiences is
 *       not audience-checked here either, exactly as on {@code /engine-rest}. So every ID
 *       token 1.5.1 or 1.5.2 accepted passes;</li>
 *   <li>{@link #forClients}: {@code cadenzaflow.sso.id-token-audiences}, which replaces the
 *       default on every issuer for a deployment that wants it narrow.</li>
 * </ul>
 *
 * <p>Neither widens {@code /engine-rest}: its own decoder is untouched, the session's identity
 * comes from the access token, and the ID token is kept only as the logout's
 * {@code id_token_hint}.</p>
 *
 * @author Gokhan Demir
 */
final class IdTokenDecoders {

  private IdTokenDecoders() {
  }

  /**
   * The default rule: per issuer, its API audiences plus {@code clientId}; no audience check
   * for an issuer that declares none.
   *
   * @param clientId the webapp registration's {@code client-id}
   */
  static JwtDecoder byDefault(
      OpenTmfSecurityProperties properties, TrustedIssuerKeys keys, String clientId) {
    return build(properties, keys, issuer -> {
      if (issuer.audiences().isEmpty()) {
        return null;
      }
      List<String> accepted = new ArrayList<>(issuer.audiences());
      if (!accepted.contains(clientId)) {
        accepted.add(clientId);
      }
      return List.copyOf(accepted);
    });
  }

  /**
   * The configured rule: on every issuer, {@code aud} must name at least one of
   * {@code audiences}.
   *
   * @param audiences the client ids an ID token may be issued to
   */
  static JwtDecoder forClients(
      OpenTmfSecurityProperties properties, TrustedIssuerKeys keys, List<String> audiences) {
    if (audiences.isEmpty()) {
      throw new IllegalArgumentException("No ID-token audience to check against");
    }
    List<String> accepted = List.copyOf(audiences);
    return build(properties, keys, issuer -> accepted);
  }

  /**
   * @param audienceRule per issuer, the accepted audiences, or {@code null} for no audience
   *     check
   */
  private static JwtDecoder build(OpenTmfSecurityProperties properties, TrustedIssuerKeys keys,
      Function<ResolvedIssuer, List<String>> audienceRule) {
    List<ResolvedIssuer> issuers = CommonConfig.resolveIssuers(properties);
    if (issuers.size() == 1 && !issuers.get(0).pinsIssuer()) {
      ResolvedIssuer single = issuers.get(0);
      return decoder(single, keys, 0, audienceRule.apply(single));
    }
    Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
    for (int i = 0; i < issuers.size(); i++) {
      ResolvedIssuer issuer = issuers.get(i);
      decoders.put(issuer.issuer(), decoder(issuer, keys, i, audienceRule.apply(issuer)));
    }
    return new CompositeJwtDecoder(decoders);
  }

  private static JwtDecoder decoder(ResolvedIssuer issuer, TrustedIssuerKeys keys, int index,
      @Nullable List<String> accepted) {
    IssuerKeys issuerKeys = keys.forIssuer(index, issuer);
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(issuerKeys.source()).build();
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    if (issuer.pinsIssuer()) {
      validators.add(new JwtIssuerValidator(issuer.issuer()));
    }
    if (accepted != null) {
      Predicate<List<String>> namesAnAcceptedClient =
          audience -> audience != null && !Collections.disjoint(audience, accepted);
      validators.add(new JwtClaimValidator<>(JwtClaimNames.AUD, namesAnAcceptedClient));
    }
    decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
    return new KeyOutageAwareJwtDecoder(decoder, issuerKeys);
  }
}
