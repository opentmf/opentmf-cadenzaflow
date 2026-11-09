package org.opentmf.cadenzaflow.config.sso;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * (CF-ID-TOKEN-AUDIENCE-CLIENT-ID-1).
 *
 * <p>An ID token is issued to the OIDC client that signed the user in, so its {@code aud} is
 * that client's id, not the API audience {@code /engine-rest} accepts (on Entra ID the two
 * differ; the API decoder refused every real Entra ID token). This decoder trusts exactly the
 * issuers {@code /engine-rest} trusts, over the same cached keys, with the same signature,
 * expiry and issuer checks, but its audience check is the client id. The API decoder is left
 * untouched, so an ID token is still refused as a bearer token.</p>
 *
 * @author Gokhan Demir
 */
final class IdTokenDecoders {

  private IdTokenDecoders() {
  }

  /**
   * @param clientId the OIDC client registration's {@code client-id}: the {@code aud} an ID
   *     token must carry
   */
  static JwtDecoder forClient(
      OpenTmfSecurityProperties properties, TrustedIssuerKeys keys, String clientId) {
    List<ResolvedIssuer> issuers = CommonConfig.resolveIssuers(properties);
    if (issuers.size() == 1 && !issuers.get(0).pinsIssuer()) {
      ResolvedIssuer single = issuers.get(0);
      return decoder(single, keys, 0, clientId);
    }
    Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
    for (int i = 0; i < issuers.size(); i++) {
      ResolvedIssuer issuer = issuers.get(i);
      decoders.put(issuer.issuer(), decoder(issuer, keys, i, clientId));
    }
    return new CompositeJwtDecoder(decoders);
  }

  private static JwtDecoder decoder(
      ResolvedIssuer issuer, TrustedIssuerKeys keys, int index, String clientId) {
    IssuerKeys issuerKeys = keys.forIssuer(index, issuer);
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(issuerKeys.source()).build();
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    if (issuer.pinsIssuer()) {
      validators.add(new JwtIssuerValidator(issuer.issuer()));
    }
    validators.add(new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, audience -> audience != null && audience.contains(clientId)));
    decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
    return new KeyOutageAwareJwtDecoder(decoder, issuerKeys);
  }
}
