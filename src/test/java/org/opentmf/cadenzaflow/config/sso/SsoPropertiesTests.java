package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
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
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> with(value).sharedCallback());
    assertTrue(e.getMessage().contains("cadenzaflow.sso.shared-callback-url"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {" https://ui.example.com/auth/callback "})
  void surroundingWhitespaceIsTrimmed(String value) {
    assertEquals(Optional.of("https://ui.example.com/auth/callback"), with(value).sharedCallback());
  }
}
