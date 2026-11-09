package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentmf.cadenzaflow.config.sso.CadenzaFlowSpringSecurityOAuth2AutoConfiguration.idTokenAudiences;
import static org.opentmf.cadenzaflow.config.sso.CadenzaFlowSpringSecurityOAuth2AutoConfiguration.webappClientId;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** CF-ID-TOKEN-AUDIENCE-CLIENT-ID-1: whose client id an {@code X-ID-Token} must name. */
class WebappClientIdTests {

  @Test
  void theOnlyRegistrationNamesTheAudienceWhateverItsId() {
    assertEquals("console", webappClientId(
        new InMemoryClientRegistrationRepository(registration("entra", "console"))));
  }

  @Test
  void withSeveralRegistrationsTheOidcOneNamesTheAudience() {
    assertEquals("console", webappClientId(new InMemoryClientRegistrationRepository(
        registration("other", "other-client"), registration("oidc", "console"))));
  }

  @Test
  void withSeveralRegistrationsAndNoOidcOneTheBootFails() {
    InMemoryClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(
        registration("a", "a-client"), registration("b", "b-client"));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> webappClientId(repository));
    assertTrue(failure.getMessage().contains("registration.oidc.client-id"),
        failure.getMessage());
  }

  /** CF-ID-TOKEN-AUDIENCES-1: by default the registration's client id, as in 1.5.2. */
  @Test
  void withoutConfiguredAudiencesTheRegistrationsClientIdIsTheAudience() {
    assertEquals(List.of("cadenzaflow"), idTokenAudiences(List.of(),
        new InMemoryClientRegistrationRepository(registration("oidc", "cadenzaflow"))));
  }

  @Test
  void configuredAudiencesReplaceTheDefault() {
    assertEquals(List.of("cadenzaflow", "dnms-config-ui"),
        idTokenAudiences(List.of("cadenzaflow", "dnms-config-ui"),
            new InMemoryClientRegistrationRepository(registration("oidc", "cadenzaflow"))));
  }

  private static ClientRegistration registration(String id, String clientId) {
    return ClientRegistration.withRegistrationId(id)
        .clientId(clientId)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
        .authorizationUri("https://idp.example.test/authorize")
        .tokenUri("https://idp.example.test/token")
        .build();
  }
}
