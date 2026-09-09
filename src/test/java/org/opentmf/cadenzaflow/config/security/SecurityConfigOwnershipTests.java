package org.opentmf.cadenzaflow.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Proves who authors {@code opentmf.security} - the image, or the deployment.
 *
 * <p>Every release up to 1.2.3 imported {@code config-security.yml} unconditionally, so a
 * deployment that mounted no security block of its own was silently governed by this image's ACL,
 * in role names that mean nothing to it. That is not hypothetical: an engine on the DNMS platform
 * chart served {@code /actuator}, {@code /actuator/metrics} and {@code /actuator/loggers}
 * unauthenticated because of it, and a tokenless {@code POST /actuator/loggers/...} returned 204
 * and moved a level from INFO to TRACE.
 *
 * <p>These tests load the REAL shipped configuration through Spring Boot's own config-data
 * machinery, so {@code spring.config.import}, profile activation and {@code spring.profiles.default}
 * behave exactly as they do at boot. No application context is started, so nothing here needs a
 * database.
 */
@DisplayName("who owns opentmf.security")
class SecurityConfigOwnershipTests {

  private static final String MANAGEMENT_WHITELIST = "opentmf.security.management.whitelist";

  /** Loads the shipped configuration exactly as boot would, under the given active profiles. */
  private static ConfigurableEnvironment configurationWith(String... activeProfiles) {
    StandardEnvironment environment = new StandardEnvironment();
    if (activeProfiles.length > 0) {
      environment.setActiveProfiles(activeProfiles);
    }
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment;
  }

  /** Loads the shipped configuration plus a mounted platform file, as a deployment would. */
  private static ConfigurableEnvironment configurationWithPlatformMount(String... activeProfiles) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles(activeProfiles);
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "mount",
                Map.of("spring.config.additional-location", "classpath:/platform-mount/")));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment;
  }

  private static List<String> stringList(ConfigurableEnvironment environment, String key) {
    return Binder.get(environment).bind(key, Bindable.listOf(String.class)).orElse(List.of());
  }

  @Nested
  @DisplayName("nobody else configuring: the standalone defaults apply")
  class Standalone {

    @Test
    @DisplayName("the standalone profile activates itself when no profile is set")
    void activateStandaloneWhenNoProfileIsSet() {
      ConfigurableEnvironment environment = configurationWith();

      assertThat(environment.getActiveProfiles()).isEmpty();
      assertThat(environment.getDefaultProfiles()).containsExactly("standalone");
    }

    @Test
    @DisplayName("a bare run still gets a jwk-set-uri, so it boots as it always did")
    void publishTheStandaloneIssuer() {
      ConfigurableEnvironment environment = configurationWith();

      assertThat(environment.getProperty("opentmf.security.jwk-set-uri"))
          .isNotBlank()
          .endsWith("/protocol/openid-connect/certs");
      assertThat(environment.getProperty("opentmf.security.user-claim")).isEqualTo("email");
    }

    @Test
    @DisplayName("the management whitelist is exactly the documented six paths")
    void publishTheDocumentedManagementWhitelist() {
      // Asserted exactly, not by `contains`: this list IS the unauthenticated surface of the
      // management port, so an entry appearing here that nobody argued for is the defect.
      assertThat(stringList(configurationWith(), MANAGEMENT_WHITELIST))
          .containsExactly(
              "/actuator",
              "/actuator/health",
              "/actuator/health/**",
              "/actuator/metrics",
              "/actuator/metrics/**",
              "/actuator/prometheus");
    }

    @Test
    @DisplayName("changing a log level is not anonymous, and reading one needs a token")
    void requireAdminToChangeALogLevel() {
      ConfigurableEnvironment environment = configurationWith();

      // Neither loggers path is whitelisted any more, so both fall back to
      // management.other-endpoints (AUTHENTICATED by default in openid-rbac-security).
      assertThat(stringList(environment, MANAGEMENT_WHITELIST))
          .noneMatch(path -> path.startsWith("/actuator/loggers"));

      assertThat(environment.getProperty("opentmf.security.management.secure-endpoints[0].method"))
          .isEqualTo("POST");
      assertThat(environment.getProperty("opentmf.security.management.secure-endpoints[0].path"))
          .isEqualTo("/actuator/loggers/**");
      assertThat(environment.getProperty("opentmf.security.management.secure-endpoints[0].roles[0]"))
          .isEqualTo("admin");

      // The endpoint stays writable on purpose - read_only here would leave the rule above with
      // nothing to authorise, which is the wrong fix for the same defect.
      assertThat(environment.getProperty("management.endpoint.loggers.access"))
          .isEqualTo("unrestricted");
    }

    @Test
    @DisplayName("env values are readable by a standalone admin, as in 1.2.3")
    void showEnvValuesToAStandaloneAdmin() {
      ConfigurableEnvironment environment = configurationWith();

      assertThat(environment.getProperty("management.endpoint.env.show-values"))
          .isEqualTo("when_authorized");
      assertThat(environment.getProperty("management.endpoint.env.roles[0]")).isEqualTo("admin");
    }
  }

  @Nested
  @DisplayName("a deployment activates a profile: the image stops being an author")
  class DeploymentOwned {

    @Test
    @DisplayName("the image contributes NO security block at all")
    void contributeNothingWhenAProfileIsActive() {
      // The assertion that encodes the whole fix. Not "our list loses" - there is no list.
      ConfigurableEnvironment environment = configurationWith("platform");

      assertThat(stringList(environment, MANAGEMENT_WHITELIST)).isEmpty();
      assertThat(environment.getProperty("opentmf.security.jwk-set-uri")).isNull();
      assertThat(environment.getProperty("opentmf.security.user-claim")).isNull();
      assertThat(environment.getProperty("opentmf.security.whitelist[0]")).isNull();
      assertThat(environment.getProperty("opentmf.security.secure-endpoints[0].path")).isNull();
    }

    @Test
    @DisplayName("with no issuer of its own it fails closed, rather than serving unauthenticated")
    void leaveNoIssuerBehindForADeploymentToInheritSilently() {
      ConfigurableEnvironment environment = configurationWith("platform");

      // openid-rbac-security refuses to start when neither is present. That refusal is the safe
      // direction, and it is why this break is loud at boot instead of silent at runtime.
      assertThat(environment.getProperty("opentmf.security.jwk-set-uri")).isNull();
      assertThat(environment.getProperty("opentmf.security.issuers[0].issuer-uri")).isNull();
    }

    @Test
    @DisplayName("env values stay masked unless the deployment opts in")
    void maskEnvValuesForADeployment() {
      // Boot's own default for show-values is `never`. Shipping only the ROLES gated would have
      // left `when_authorized` with an empty roles list, which Boot reads as "every authenticated
      // user is authorized" - strictly looser than 1.2.3, where mismatched role names kept the
      // values masked.
      ConfigurableEnvironment environment = configurationWith("platform");

      assertThat(environment.getProperty("management.endpoint.env.show-values")).isNull();
      assertThat(environment.getProperty("management.endpoint.env.roles[0]")).isNull();
    }

    @Test
    @DisplayName("endpoint exposure and probes are NOT deployment-owned, and still ship")
    void keepTheNonSecurityActuatorSettings() {
      ConfigurableEnvironment environment = configurationWith("platform");

      assertThat(environment.getProperty("management.server.port")).isEqualTo("16000");
      assertThat(environment.getProperty("management.endpoints.access.default"))
          .isEqualTo("read_only");
      assertThat(environment.getProperty("management.endpoint.health.probes.enabled"))
          .isEqualTo("true");
    }
  }

  @Nested
  @DisplayName("a deployment mounts its own block: it is the sole author")
  class MountedDeployment {

    @Test
    @DisplayName("the effective whitelist is the mounted one, entry for entry")
    void letTheMountedFileOwnTheWhitelist() {
      // LENGTH, not membership. A leftover entry past the end of the mounted list is exactly what
      // a `contains` check cannot see, and it is what the original index-merge theory predicted.
      List<String> whitelist =
          stringList(configurationWithPlatformMount("platform"), MANAGEMENT_WHITELIST);

      assertThat(whitelist)
          .hasSize(3)
          .containsExactly("/actuator/health", "/actuator/health/**", "/actuator/prometheus");
      assertThat(whitelist).doesNotContain("/actuator", "/actuator/metrics", "/actuator/loggers");
    }

    @Test
    @DisplayName("lists REPLACE across sources - they do not merge by index")
    void replaceListsAcrossSourcesRatherThanMergingThem() {
      // Documents the mechanism the estate corrected by measurement on 2026-09-09. Spring binds a
      // list from the highest-precedence source that defines it; maps merge per key, lists do not.
      // Kept as a test because getting this wrong once cost a round of analysis.
      ConfigurableEnvironment mounted = configurationWithPlatformMount("platform");

      assertThat(stringList(mounted, MANAGEMENT_WHITELIST))
          .hasSize(3)
          .isNotEqualTo(stringList(configurationWith(), MANAGEMENT_WHITELIST));
    }

    @Test
    @DisplayName("a second profile REPLACES the first's list, it does not blend with it")
    void replaceBetweenProfilesToo() {
      // Replacement is not a classpath-versus-file rule; it holds profile-to-profile. A
      // deployment that splits its security block across two profiles loses most of it, and the
      // first symptom is a pod that never goes Ready because /actuator/health/readiness answers
      // 401. Documented here because README §3.5 warns consumers about exactly this.
      List<String> whitelist =
          stringList(
              configurationWithPlatformMount("platform", "narrow"), MANAGEMENT_WHITELIST);

      assertThat(whitelist).containsExactly("/actuator/health");
      assertThat(whitelist).doesNotContain("/actuator/health/**", "/actuator/prometheus");
    }

    @Test
    @DisplayName("the mounted identity and roles are the ones in force")
    void letTheMountedFileOwnIdentity() {
      ConfigurableEnvironment mounted = configurationWithPlatformMount("platform");

      assertThat(mounted.getProperty("opentmf.security.user-claim")).isEqualTo("sub");
      assertThat(mounted.getProperty("opentmf.security.issuers[0].authorities-claim"))
          .isEqualTo("platform_roles");
      assertThat(mounted.getProperty("opentmf.security.management.secure-endpoints[0].roles[0]"))
          .isEqualTo("dnms-admin");
    }
  }
}
