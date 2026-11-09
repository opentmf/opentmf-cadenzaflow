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
 * CF-ID-TOKEN-DECODER-BOOT-1, the DNMS stack's shape that crash-looped 1.5.3: single-issuer
 * mode ({@code opentmf.security.jwk-set-uri}, no issuer pinned, no audiences declared) with
 * the shared sign-in on and {@code id-token-audiences} unset. The application must boot,
 * and the shared page's ID token, issued to the console's own client, must sign in.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class TokenLoginSingleIssuerIT extends TokenLoginItSupport {

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    sharedSignIn(r);
    r.add("opentmf.security.jwk-set-uri", () -> ISSUER.jwkSetUri);
  }

  @Test
  void bootsAndTheConsolesIdTokenSignsIn() throws Exception {
    HttpResponse<String> login = login(ISSUER.valid(), ISSUER.idToken(CONSOLE_CLIENT_ID));

    assertEquals(204, login.statusCode(), login.body());
    assertTrue(sessionCookie(login).isPresent());
  }

  @Test
  void anIdTokenSignedByAStrangerIsStillRefused() throws Exception {
    String forged = new LoginWithTokenIT.TestIssuer().idToken(CONSOLE_CLIENT_ID);

    HttpResponse<String> login = login(ISSUER.valid(), forged);

    assertEquals(401, login.statusCode());
    assertTrue(sessionCookie(login).isEmpty());
  }
}
