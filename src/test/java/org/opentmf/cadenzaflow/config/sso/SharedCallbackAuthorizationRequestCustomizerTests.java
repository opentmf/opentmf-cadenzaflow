package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

class SharedCallbackAuthorizationRequestCustomizerTests {

  private static final String TEMPLATE_REDIRECT =
      "http://localhost:8091/cadenzaflow/v1/login/oauth2/code/oidc";
  private static final String SHARED = "https://ui.example.com/auth/callback";

  private static OAuth2AuthorizationRequest customise(String sharedCallbackUrl) {
    OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("http://keycloak.test/realms/dsync/protocol/openid-connect/auth")
        .clientId("camunda-identity-service")
        .redirectUri(TEMPLATE_REDIRECT)
        .state("spring-default-state");
    new SharedCallbackAuthorizationRequestCustomizer(sharedCallbackUrl).accept(builder);
    return builder.build();
  }

  @Test
  void sharedCallbackReplacesTheRedirectUri() {
    assertEquals(SHARED, customise(SHARED).getRedirectUri());
  }

  @Test
  void withoutSharedCallbackTheTemplateRedirectUriStays() {
    assertEquals(TEMPLATE_REDIRECT, customise(null).getRedirectUri());
  }

  @Test
  void stateIsAFreshKeyBehindTheCfPrefix() {
    OAuth2AuthorizationRequest first = customise(SHARED);
    OAuth2AuthorizationRequest second = customise(SHARED);

    assertTrue(first.getState().startsWith("cf."), first.getState());
    assertTrue(first.getState().length() > "cf.".length() + 20, first.getState());
    assertNotEquals("cf.spring-default-state", first.getState());
    assertNotEquals(first.getState(), second.getState());
  }

  @Test
  void pkceIsAlwaysOnWithS256() {
    for (String shared : new String[] {SHARED, null}) {
      OAuth2AuthorizationRequest request = customise(shared);

      assertEquals("S256",
          request.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE_METHOD));
      assertNotNull(request.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE));
      assertNotNull(request.getAttribute(PkceParameterNames.CODE_VERIFIER));
    }
  }
}
