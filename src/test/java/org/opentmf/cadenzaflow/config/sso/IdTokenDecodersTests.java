package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.security.config.ServletJwtSupport;
import org.opentmf.security.jwks.TrustedIssuerKeys;
import org.opentmf.security.model.IssuerProperties;
import org.opentmf.security.model.OpenTmfSecurityProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * CF-ID-TOKEN-AUDIENCE-CLIENT-ID-1 / CF-SSO-ENTRA-SHAPED-TESTS-1: the {@code X-ID-Token} is an
 * ID token, whose {@code aud} is the OIDC client id. On Entra ID that is NOT the API audience
 * {@code /engine-rest} accepts.
 */
class IdTokenDecodersTests {

  private static final String CLIENT_ID = "console-client-id";
  private static final String API_AUDIENCE = "api://engine";
  private static final String ISSUER = "https://login.example.test/tenant/v2.0";
  private static final String OTHER_ISSUER = "https://other.example.test/realms/x";

  private final RSAKey key = rsaKey("k1");
  private final RSAKey otherKey = rsaKey("k2");

  @Test
  void anEntraShapedIdTokenIsAcceptedHereAndStillRefusedByTheApiDecoder() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));
    String idToken = mint(key, ISSUER, List.of(CLIENT_ID), Instant.now().plusSeconds(300));

    assertEquals(CLIENT_ID, idTokenDecoder(properties).decode(idToken).getAudience().get(0));
    JwtDecoder api = new ServletJwtSupport(properties, new TrustedIssuerKeys(properties))
        .getDecoder();
    JwtException refused = assertThrows(JwtException.class, () -> api.decode(idToken));
    assertTrue(refused.getMessage().contains("aud"), refused.getMessage());
  }

  @Test
  void aKeycloakShapedIdTokenCarryingBothAudiencesIsAccepted() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));

    String idToken =
        mint(key, ISSUER, List.of(API_AUDIENCE, CLIENT_ID), Instant.now().plusSeconds(300));

    assertTrue(idTokenDecoder(properties).decode(idToken).getAudience().contains(CLIENT_ID));
  }

  @Test
  void anIdTokenForAForeignAudienceIsRefused() {
    JwtDecoder decoder = idTokenDecoder(issuers(entry(ISSUER, key, API_AUDIENCE)));
    String foreign = mint(key, ISSUER, List.of("someone-else"), Instant.now().plusSeconds(300));

    JwtException refused = assertThrows(JwtException.class, () -> decoder.decode(foreign));
    assertTrue(refused.getMessage().contains("aud"), refused.getMessage());
  }

  @Test
  void anIdTokenCarryingOnlyTheApiAudienceIsRefused() {
    JwtDecoder decoder = idTokenDecoder(issuers(entry(ISSUER, key, API_AUDIENCE)));
    String apiOnly = mint(key, ISSUER, List.of(API_AUDIENCE), Instant.now().plusSeconds(300));

    assertThrows(JwtException.class, () -> decoder.decode(apiOnly));
  }

  @Test
  void anExpiredIdTokenIsRefused() {
    JwtDecoder decoder = idTokenDecoder(issuers(entry(ISSUER, key, API_AUDIENCE)));

    // Past Spring's 60 s clock-skew allowance.
    String expired = mint(key, ISSUER, List.of(CLIENT_ID), Instant.now().minusSeconds(300));

    assertThrows(JwtException.class, () -> decoder.decode(expired));
  }

  @Test
  void anIdTokenSignedByAnotherKeyIsRefused() {
    JwtDecoder decoder = idTokenDecoder(issuers(entry(ISSUER, key, API_AUDIENCE)));

    String forged = mint(otherKey, ISSUER, List.of(CLIENT_ID), Instant.now().plusSeconds(300));

    assertThrows(JwtException.class, () -> decoder.decode(forged));
  }

  @Test
  void eachIssuerIsCheckedWithItsOwnKeysAndAnUnknownIssuerIsRefused() {
    JwtDecoder decoder = idTokenDecoder(issuers(
        entry(ISSUER, key, API_AUDIENCE), entry(OTHER_ISSUER, otherKey, API_AUDIENCE)));
    Instant exp = Instant.now().plusSeconds(300);

    String first = mint(key, ISSUER, List.of(CLIENT_ID), exp);
    String second = mint(otherKey, OTHER_ISSUER, List.of(CLIENT_ID), exp);
    String wrongKey = mint(key, OTHER_ISSUER, List.of(CLIENT_ID), exp);
    String stranger = mint(key, "https://stranger.example.test", List.of(CLIENT_ID), exp);

    assertEquals(ISSUER, decoder.decode(first).getIssuer().toString());
    assertEquals(OTHER_ISSUER, decoder.decode(second).getIssuer().toString());
    assertThrows(JwtException.class, () -> decoder.decode(wrongKey));
    assertThrows(JwtException.class, () -> decoder.decode(stranger));
  }

  /**
   * CF-ID-TOKEN-AUDIENCES-1: a Keycloak stack where the shared page's console client
   * (dnms-config-ui) differs from the engine's registration (cadenzaflow). Both listed.
   */
  @Test
  void withTwoClientsListedAnIdTokenOfEitherIsAccepted() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));
    JwtDecoder decoder = IdTokenDecoders.forClients(properties,
        new TrustedIssuerKeys(properties), List.of("cadenzaflow", "dnms-config-ui"));
    Instant exp = Instant.now().plusSeconds(300);
    String console = mint(key, ISSUER, List.of("dnms-config-ui"), exp);
    String engine = mint(key, ISSUER, List.of("cadenzaflow"), exp);
    String foreign = mint(key, ISSUER, List.of(CLIENT_ID), exp);

    assertEquals(List.of("dnms-config-ui"), decoder.decode(console).getAudience());
    assertEquals(List.of("cadenzaflow"), decoder.decode(engine).getAudience());
    assertThrows(JwtException.class, () -> decoder.decode(foreign));
  }

  /**
   * CF-ID-TOKEN-AUDIENCES-1, the default: an issuer's API audiences plus the registration's
   * client id. The VFDE shape: whatever 1.5.1 (API audience) or 1.5.2 (client id) accepted.
   */
  @Test
  void byDefaultTheClientIdAndTheIssuersApiAudiencesPass() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));
    JwtDecoder decoder =
        IdTokenDecoders.byDefault(properties, new TrustedIssuerKeys(properties), CLIENT_ID);
    Instant exp = Instant.now().plusSeconds(300);
    String client = mint(key, ISSUER, List.of(CLIENT_ID), exp);
    String api = mint(key, ISSUER, List.of(API_AUDIENCE), exp);
    String foreign = mint(key, ISSUER, List.of("someone-else"), exp);

    assertEquals(List.of(CLIENT_ID), decoder.decode(client).getAudience());
    assertEquals(List.of(API_AUDIENCE), decoder.decode(api).getAudience());
    assertThrows(JwtException.class, () -> decoder.decode(foreign));
  }

  /**
   * The Keycloak stack shape (Gökhan's stack after the 1.5.2 roll): the realm's issuer entry
   * declares no audiences, so /engine-rest checks none, and the shared page's ID token names
   * its own console client. By default it passes, as in 1.5.1; keys and expiry still apply.
   */
  @Test
  void byDefaultAnIssuerWithoutAudiencesIsNotAudienceCheckedAsOnTheRestApi() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, null));
    JwtDecoder decoder =
        IdTokenDecoders.byDefault(properties, new TrustedIssuerKeys(properties), "cadenzaflow");
    String console = mint(key, ISSUER, List.of("dnms-config-ui"), Instant.now().plusSeconds(300));
    String forged =
        mint(otherKey, ISSUER, List.of("dnms-config-ui"), Instant.now().plusSeconds(300));
    String expired =
        mint(key, ISSUER, List.of("dnms-config-ui"), Instant.now().minusSeconds(300));

    assertEquals(List.of("dnms-config-ui"), decoder.decode(console).getAudience());
    assertThrows(JwtException.class, () -> decoder.decode(forged));
    assertThrows(JwtException.class, () -> decoder.decode(expired));
  }

  /** A set cadenzaflow.sso.id-token-audiences replaces the default, on every issuer. */
  @Test
  void aConfiguredListNarrowsEvenAnIssuerWithoutAudiences() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, null));
    JwtDecoder decoder = CadenzaFlowSpringSecurityOAuth2AutoConfiguration.idTokenDecoder(
        List.of("cadenzaflow"), properties, new TrustedIssuerKeys(properties),
        // No oidc registration among several: a set list never needs one.
        new InMemoryClientRegistrationRepository(
            WebappClientIdTests.registration("a", "a-client"),
            WebappClientIdTests.registration("b", "b-client")));
    Instant exp = Instant.now().plusSeconds(300);
    String engine = mint(key, ISSUER, List.of("cadenzaflow"), exp);
    String console = mint(key, ISSUER, List.of("dnms-config-ui"), exp);

    assertEquals(List.of("cadenzaflow"), decoder.decode(engine).getAudience());
    assertThrows(JwtException.class, () -> decoder.decode(console));
  }

  @Test
  void unsetTheWiringUsesTheDefaultWithTheRegistrationsClientId() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));
    JwtDecoder decoder = CadenzaFlowSpringSecurityOAuth2AutoConfiguration.idTokenDecoder(
        List.of(), properties, new TrustedIssuerKeys(properties),
        new InMemoryClientRegistrationRepository(
            WebappClientIdTests.registration("oidc", "cadenzaflow")));
    Instant exp = Instant.now().plusSeconds(300);
    String engine = mint(key, ISSUER, List.of("cadenzaflow"), exp);
    String api = mint(key, ISSUER, List.of(API_AUDIENCE), exp);
    String console = mint(key, ISSUER, List.of("dnms-config-ui"), exp);

    assertEquals(List.of("cadenzaflow"), decoder.decode(engine).getAudience());
    assertEquals(List.of(API_AUDIENCE), decoder.decode(api).getAudience());
    assertThrows(JwtException.class, () -> decoder.decode(console));
  }

  @Test
  void anEmptyAudienceListIsRefusedAtConstruction() {
    OpenTmfSecurityProperties properties = issuers(entry(ISSUER, key, API_AUDIENCE));
    TrustedIssuerKeys keys = new TrustedIssuerKeys(properties);
    List<String> none = List.of();

    assertThrows(IllegalArgumentException.class,
        () -> IdTokenDecoders.forClients(properties, keys, none));
  }

  @Test
  void singleIssuerModeChecksTheClientIdToo() {
    OpenTmfSecurityProperties properties = new OpenTmfSecurityProperties();
    properties.setJwkSetUri(jwks(key));
    JwtDecoder decoder = idTokenDecoder(properties);
    Instant exp = Instant.now().plusSeconds(300);

    String idToken = mint(key, ISSUER, List.of(CLIENT_ID), exp);
    String foreign = mint(key, ISSUER, List.of("someone-else"), exp);

    assertEquals("subject-id", decoder.decode(idToken).getSubject());
    assertThrows(JwtException.class, () -> decoder.decode(foreign));
  }

  private static JwtDecoder idTokenDecoder(OpenTmfSecurityProperties properties) {
    return IdTokenDecoders.forClients(
        properties, new TrustedIssuerKeys(properties), List.of(CLIENT_ID));
  }

  private static OpenTmfSecurityProperties issuers(IssuerProperties... entries) {
    OpenTmfSecurityProperties properties = new OpenTmfSecurityProperties();
    properties.setIssuers(List.of(entries));
    return properties;
  }

  private static IssuerProperties entry(String issuer, RSAKey key, String audience) {
    IssuerProperties entry = new IssuerProperties();
    entry.setIssuer(issuer);
    entry.setJwkSetUri(jwks(key));
    entry.setAudiences(audience == null ? List.of() : List.of(audience));
    return entry;
  }

  private static FileSystemResource jwks(RSAKey key) {
    try {
      Path file = Files.createTempFile("jwks", ".json");
      Files.writeString(file, new JWKSet(key.toPublicJWK()).toString());
      file.toFile().deleteOnExit();
      return new FileSystemResource(file);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey rsaKey(String id) {
    try {
      return new RSAKeyGenerator(2048).keyID(id).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String mint(RSAKey key, String issuer, List<String> audience, Instant exp) {
    try {
      SignedJWT jwt = new SignedJWT(
          new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject("subject-id")
              .audience(audience)
              .issueTime(Date.from(exp.minusSeconds(600)))
              .expirationTime(Date.from(exp))
              .claim("nonce", "n-0S6_WzA2Mj")
              .build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
