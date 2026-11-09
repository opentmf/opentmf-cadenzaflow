package org.opentmf.cadenzaflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CF-ID-TOKEN-DECODER-BOOT-1, the VFDE shape: a pinned {@code issuers[]} entry that declares
 * an API audience, {@code id-token-audiences} unset. The default accepts an ID token issued
 * to the webapp registration or to the API audience, and nothing else.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class TokenLoginDeclaredAudiencesIT extends TokenLoginItSupport {

  private static final String API_AUDIENCE = "it-client";

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    sharedSignIn(r);
    r.add("opentmf.security.jwk-set-uri", () -> "");
    r.add("opentmf.security.issuers[0].name", () -> "it");
    r.add("opentmf.security.issuers[0].issuer", () -> ISSUER.issuer);
    r.add("opentmf.security.issuers[0].jwk-set-uri", () -> ISSUER.jwkSetUri);
    r.add("opentmf.security.issuers[0].audiences[0]", () -> API_AUDIENCE);
  }

  @Test
  void anIdTokenIssuedToTheWebappRegistrationSignsIn() throws Exception {
    HttpResponse<String> login = login(ISSUER.valid(), ISSUER.idToken(CLIENT_ID));

    assertEquals(204, login.statusCode(), login.body());
    assertTrue(sessionCookie(login).isPresent());
  }

  @Test
  void anIdTokenCarryingTheApiAudienceSignsIn() throws Exception {
    HttpResponse<String> login = login(ISSUER.valid(), ISSUER.idToken(API_AUDIENCE));

    assertEquals(204, login.statusCode(), login.body());
  }

  @Test
  void anIdTokenOfAnUnlistedClientIsRefused() throws Exception {
    HttpResponse<String> login = login(ISSUER.valid(), ISSUER.idToken(CONSOLE_CLIENT_ID));

    assertEquals(401, login.statusCode());
    assertTrue(sessionCookie(login).isEmpty());
  }
}
