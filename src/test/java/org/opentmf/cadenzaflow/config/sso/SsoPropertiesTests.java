package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SsoPropertiesTests {

  private static SsoProperties with(String value) {
    SsoProperties properties = new SsoProperties();
    properties.setSharedCallbackUrl(value);
    return properties;
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void blankMeansNoSharedCallback(String value) {
    assertEquals(Optional.empty(), with(value).sharedCallback());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://ui.example.com/auth/callback",
      "http://localhost:8080/auth/callback",
      "HTTPS://ui.example.com/auth/callback"})
  void absoluteHttpUrlIsTaken(String value) {
    assertEquals(Optional.of(value), with(value).sharedCallback());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/auth/callback",
      "ui.example.com/auth/callback",
      "ftp://ui.example.com/auth/callback",
      "https:///auth/callback",
      "https://ui.example.com/auth/callback?x=1",
      "https://ui.example.com/auth/callback#f",
      "https://ui example.com/auth/callback"})
  void anythingElseFailsAtBoot(String value) {
    SsoProperties properties = with(value);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, properties::sharedCallback);
    assertTrue(e.getMessage().contains("cadenzaflow.sso.shared-callback-url"), e.getMessage());
  }

  @Test
  void signInThroughTheSharedCallbackIsOffByDefault() {
    SsoProperties properties = with("https://ui.example.com/auth/callback");

    assertFalse(properties.isSharedCallbackSignIn());
    assertEquals(Optional.empty(), properties.sharedCallbackSignInUrl());
  }

  @Test
  void signInSwitchedOnUsesTheSharedCallback() {
    SsoProperties properties = with("https://ui.example.com/auth/callback");
    properties.setSharedCallbackSignIn(true);

    assertEquals(
        Optional.of("https://ui.example.com/auth/callback"),
        properties.sharedCallbackSignInUrl());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void signInSwitchedOnWithoutAUrlFailsAtBoot(String value) {
    SsoProperties properties = with(value);
    properties.setSharedCallbackSignIn(true);

    IllegalStateException e =
        assertThrows(IllegalStateException.class, properties::sharedCallbackSignInUrl);
    assertTrue(e.getMessage().contains("shared-callback-sign-in"), e.getMessage());
  }

  @Test
  void loginPageIsOffByDefaultAndTakenWhenSet() {
    SsoProperties properties = new SsoProperties();
    assertEquals(Optional.empty(), properties.loginPage());
    assertEquals(SsoProperties.DEFAULT_BEARER_SESSION_MAX, properties.getBearerSessionMax());

    properties.setLoginPageUrl("https://ui.example.com/auth/cockpit");
    assertEquals(Optional.of("https://ui.example.com/auth/cockpit"), properties.loginPage());
  }

  @Test
  void theBearerLoginUserClaimIsUnsetByDefaultAndTrimmedWhenSet() {
    SsoProperties properties = new SsoProperties();
    assertEquals(Optional.empty(), properties.getBearerLogin().userClaimOverride());

    properties.getBearerLogin().setUserClaim("   ");
    assertEquals(Optional.empty(), properties.getBearerLogin().userClaimOverride());

    properties.getBearerLogin().setUserClaim(" email ");
    assertEquals(Optional.of("email"), properties.getBearerLogin().userClaimOverride());
  }

  @Test
  void aMalformedLoginPageFailsAtBootNamingItsOwnKey() {
    SsoProperties properties = new SsoProperties();
    properties.setLoginPageUrl("/auth/cockpit");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, properties::loginPage);
    assertTrue(e.getMessage().contains("cadenzaflow.sso.login-page-url"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {" https://ui.example.com/auth/callback "})
  void surroundingWhitespaceIsTrimmed(String value) {
    assertEquals(Optional.of("https://ui.example.com/auth/callback"), with(value).sharedCallback());
  }

  @Test
  void idTokenAudiencesDefaultToEmptyWhichMeansTheRegistrationsClientId() {
    assertEquals(List.of(), new SsoProperties().idTokenAudienceOverride());
  }

  @Test
  void idTokenAudiencesAreTrimmedWithoutBlanksOrDuplicates() {
    SsoProperties properties = new SsoProperties();
    properties.setIdTokenAudiences(
        Arrays.asList(" cadenzaflow ", "", null, "dnms-config-ui", "cadenzaflow"));

    assertEquals(List.of("cadenzaflow", "dnms-config-ui"), properties.idTokenAudienceOverride());
    assertEquals(5, properties.getIdTokenAudiences().size());
  }

  @Test
  void aNullIdTokenAudienceListMeansTheDefault() {
    SsoProperties properties = new SsoProperties();
    properties.setIdTokenAudiences(null);

    assertEquals(List.of(), properties.idTokenAudienceOverride());
  }
}
