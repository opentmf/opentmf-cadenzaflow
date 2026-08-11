package org.opentmf.cadenzaflow;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.identity.Group;
import org.cadenzaflow.bpm.engine.identity.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full application with {@code plugin.identity.provider=entra} against an
 * embedded WireMock playing the Entra ID token endpoint and Microsoft Graph. Covers what
 * the plugin-level tests cannot: yml property binding, provider-switch bean selection,
 * engine mounting, the startup admin bootstrap against the real (Testcontainers Postgres)
 * database, and identity queries through the engine's command context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class EntraIdentityIT {

  private static final String EMAIL = "gokhan.demir@pia-team.com";

  // started before the Spring context so @DynamicPropertySource can hand out its URL
  private static final WireMockServer GRAPH = new WireMockServer(0);

  static {
    GRAPH.start();
    GRAPH.stubFor(post(urlPathEqualTo("/test-tenant/oauth2/v2.0/token")).willReturn(okJson(
        "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    // admin-group bootstrap + group-by-displayName lookups
    GRAPH.stubFor(get(urlPathEqualTo("/v1.0/groups"))
        .withQueryParam("$filter",
            equalTo("displayName eq 'camunda-admin' and securityEnabled eq true"))
        .willReturn(okJson("{\"value\":[{\"id\":\"g-admin\",\"displayName\":\"camunda-admin\","
            + "\"securityEnabled\":true}]}")));
    // user resolution by mail
    GRAPH.stubFor(get(urlPathEqualTo("/v1.0/users"))
        .withQueryParam("$filter", equalTo("mail eq '" + EMAIL + "'"))
        .willReturn(okJson("{\"value\":[{\"id\":\"u-1\",\"mail\":\"" + EMAIL + "\","
            + "\"userPrincipalName\":\"gokhan@test-tenant\",\"givenName\":\"Gokhan\","
            + "\"surname\":\"Demir\",\"displayName\":\"Gokhan Demir\"}]}")));
    // the user's transitive groups
    GRAPH.stubFor(get(urlPathEqualTo("/v1.0/users/u-1/transitiveMemberOf/microsoft.graph.group"))
        .willReturn(okJson("{\"value\":["
            + "{\"id\":\"g-admin\",\"displayName\":\"camunda-admin\",\"securityEnabled\":true},"
            + "{\"id\":\"g-2\",\"displayName\":\"m365-team\",\"securityEnabled\":false}]}")));
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    r.add("plugin.identity.provider", () -> "entra");
    r.add("plugin.identity.entra.tenant-id", () -> "test-tenant");
    r.add("plugin.identity.entra.client-id", () -> "test-client");
    r.add("plugin.identity.entra.client-secret", () -> "test-secret");
    r.add("plugin.identity.entra.authority-host", GRAPH::baseUrl);
    r.add("plugin.identity.entra.graph-base-url", () -> GRAPH.baseUrl() + "/v1.0");
    r.add("plugin.identity.entra.administrator-group-name", () -> "camunda-admin");
    r.add("plugin.identity.entra.use-group-display-name-as-camunda-group-id", () -> "true");
    r.add("plugin.identity.entra.cache-enabled", () -> "false");
  }

  @AfterAll
  static void stopWireMock() {
    GRAPH.stop();
  }

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private ProcessEngine processEngine;

  @Test
  void providerSwitchActivatesEntraAndLeavesKeycloakDormant() {
    Assertions.assertEquals(1,
        applicationContext.getBeanNamesForType(
            org.opentmf.cadenzaflow.config.EntraIdentityProvider.class).length);
    Assertions.assertEquals(0,
        applicationContext.getBeanNamesForType(
            org.opentmf.cadenzaflow.config.KeycloakIdentityProvider.class).length);
  }

  @Test
  void ymlPropertiesBindToThePluginConfiguration() {
    var plugin = applicationContext.getBean(org.opentmf.cadenzaflow.config.EntraIdentityProvider.class);
    Assertions.assertEquals("test-tenant", plugin.getTenantId());
    Assertions.assertEquals("test-client", plugin.getClientId());
    Assertions.assertTrue(plugin.isUseGroupDisplayNameAsCamundaGroupId());
    Assertions.assertEquals("camunda-admin", plugin.getAdministratorGroupName());
  }

  @Test
  void startupBootstrapGrantsAdminAuthorizationsInTheDatabase() {
    // postProcessEngineBuild resolved 'camunda-admin' via Graph and persisted grants
    Assertions.assertTrue(processEngine.getAuthorizationService().createAuthorizationQuery()
        .groupIdIn("camunda-admin").count() > 0);
    Assertions.assertTrue(((org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl)
        processEngine.getProcessEngineConfiguration()).getAdminGroups()
        .contains("camunda-admin"));
  }

  @Test
  void engineResolvesUserThroughGraphViaCommandContext() {
    User user = processEngine.getIdentityService().createUserQuery().userId(EMAIL).singleResult();
    Assertions.assertNotNull(user);
    Assertions.assertEquals(EMAIL, user.getId());
    Assertions.assertEquals("Gokhan", user.getFirstName());
  }

  @Test
  void engineResolvesTransitiveGroupsWithDisplayNameIds() {
    var groups = processEngine.getIdentityService().createGroupQuery().groupMember(EMAIL).list();
    Assertions.assertEquals(1, groups.size());
    Group group = groups.get(0);
    Assertions.assertEquals("camunda-admin", group.getId());
    Assertions.assertEquals("SYSTEM", group.getType());
  }

  @Test
  void passwordLoginIsRejectedSsoOnly() {
    Assertions.assertFalse(
        processEngine.getIdentityService().checkPassword(EMAIL, "any-password"));
  }
}
