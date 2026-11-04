package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class BearerSessionAuthenticationTests {

  private static final BearerSessionDetails DETAILS =
      new BearerSessionDetails("id-token", Instant.parse("2026-11-03T16:00:00Z"));

  @Test
  void copiesNameAuthoritiesAndTokenFromTheValidatedToken() {
    Jwt jwt = jwt("access-token");
    JwtAuthenticationToken validated = new JwtAuthenticationToken(
        jwt, List.of(new SimpleGrantedAuthority("admin")), "gokhan@example.test");

    BearerSessionAuthentication session = BearerSessionAuthentication.of(validated, DETAILS);

    assertEquals("gokhan@example.test", session.getName());
    assertEquals("gokhan@example.test", session.getPrincipal());
    assertEquals(validated.getAuthorities(), session.getAuthorities());
    assertSame(jwt, session.getToken());
    assertSame(DETAILS, session.getDetails());
    assertEquals("", session.getCredentials());
    assertTrue(session.isAuthenticated());
  }

  @Test
  void equalityFollowsTheTokenAndTheAuthenticationFields() {
    BearerSessionAuthentication one = session("access-token");
    BearerSessionAuthentication same = session("access-token");
    BearerSessionAuthentication other = session("another-token");

    assertEquals(one, same);
    assertEquals(one.hashCode(), same.hashCode());
    assertNotEquals(one, other);
    assertNotEquals("access-token", one);
  }

  private static BearerSessionAuthentication session(String tokenValue) {
    return new BearerSessionAuthentication(
        jwt(tokenValue), "gokhan@example.test", List.of(), DETAILS);
  }

  private static Jwt jwt(String tokenValue) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "RS256")
        .claim("email", "gokhan@example.test")
        .issuedAt(Instant.parse("2026-11-03T16:00:00Z"))
        .expiresAt(Instant.parse("2026-11-03T16:05:00Z"))
        .build();
  }
}
