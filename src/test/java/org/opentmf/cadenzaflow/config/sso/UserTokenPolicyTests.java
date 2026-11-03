package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentmf.security.config.ResolvedIssuer;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * D-457: only a delegated user token opens a webapp session. The Entra cells are judged on
 * the claims Entra issues: a delegated token carries {@code scp} and {@code idtyp=user}
 * (or none); a client-credentials token carries {@code idtyp=app} and {@code roles}, no
 * {@code scp}.
 */
class UserTokenPolicyTests {

  private static final String ENTRA = "https://login.microsoftonline.com/tenant-id/v2.0";
  private static final String KEYCLOAK = "https://keycloak.test/realms/dnms";

  private final UserTokenPolicy policy = new UserTokenPolicy(List.of(
      issuer(ENTRA, "email"), issuer(KEYCLOAK, "email")));

  @Test
  void anEntraDelegatedUserTokenIsAccepted() {
    Jwt jwt = jwt(ENTRA, Map.of("email", "gokhan@example.test", "scp", "dnms.api",
        "idtyp", "user"));
    assertEquals(Optional.empty(), policy.refusal(jwt, "gokhan@example.test"));
  }

  @Test
  void anEntraClientCredentialsTokenIsRefused() {
    Jwt jwt = jwt(ENTRA, Map.of("email", "worker@example.test", "idtyp", "app",
        "roles", List.of("dnms-write")));
    assertRefused(policy.refusal(jwt, "worker@example.test"), "idtyp=app");
  }

  @Test
  void anEntraTokenWithoutScopeIsRefusedEvenWithoutIdtyp() {
    // idtyp is an optional claim on Entra; without it, the missing scp alone decides.
    Jwt jwt = jwt(ENTRA, Map.of("email", "worker@example.test"));
    assertRefused(policy.refusal(jwt, "worker@example.test"), "without 'scp'");
  }

  @Test
  void aKeycloakUserTokenIsAccepted() {
    Jwt jwt = jwt(KEYCLOAK, Map.of("email", "gokhan@example.test",
        "preferred_username", "gokhan"));
    assertEquals(Optional.empty(), policy.refusal(jwt, "gokhan@example.test"));
  }

  @Test
  void aKeycloakServiceAccountTokenIsRefused() {
    Jwt jwt = jwt(KEYCLOAK, Map.of("email", "flow@example.test",
        "preferred_username", "service-account-dnms-flow"));
    assertRefused(policy.refusal(jwt, "flow@example.test"), "service-account");
  }

  @Test
  void aTokenWithoutTheUserClaimIsRefused() {
    Jwt jwt = jwt(KEYCLOAK, Map.of("preferred_username", "gokhan"));
    assertRefused(policy.refusal(jwt, "subject-id"), "'email'");
  }

  @Test
  void aBlankUserClaimIsRefused() {
    Jwt jwt = jwt(KEYCLOAK, Map.of("email", " "));
    assertRefused(policy.refusal(jwt, "subject-id"), "'email'");
  }

  @Test
  void anIssuerWithoutConfigurationIsRefused() {
    Jwt jwt = jwt("https://elsewhere.test", Map.of("email", "gokhan@example.test"));
    assertRefused(policy.refusal(jwt, "gokhan@example.test"), "no trusted issuer");
  }

  @Test
  void singleIssuerModeAppliesItsOneEntryToEveryToken() {
    UserTokenPolicy single = new UserTokenPolicy(List.of(issuer(null, "email")));
    Jwt jwt = jwt(KEYCLOAK, Map.of("email", "gokhan@example.test"));
    assertEquals(Optional.empty(), single.refusal(jwt, "gokhan@example.test"));
  }

  private static void assertRefused(Optional<String> refusal, String reasonPart) {
    assertTrue(refusal.isPresent(), "expected a refusal");
    assertTrue(refusal.get().contains(reasonPart), refusal.get());
  }

  private static ResolvedIssuer issuer(String iss, String userClaim) {
    return new ResolvedIssuer("it", iss, null, userClaim, List.of(), "roles", List.of(), null);
  }

  private static Jwt jwt(String iss, Map<String, Object> claims) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("iss", iss)
        .claims(c -> c.putAll(claims))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
