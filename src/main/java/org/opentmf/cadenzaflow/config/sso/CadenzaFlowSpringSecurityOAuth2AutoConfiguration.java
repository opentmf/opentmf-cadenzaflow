package org.opentmf.cadenzaflow.config.sso;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.annotation.Nullable;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cadenzaflow.bpm.engine.spring.SpringProcessEngineServicesConfiguration;
import org.cadenzaflow.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cadenzaflow.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cadenzaflow.bpm.spring.boot.starter.property.WebappProperty;
import org.cadenzaflow.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.oauth2.client.autoconfigure.ConditionalOnOAuth2ClientRegistrationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * @author Abdullah Beker
 */
@AutoConfigureAfter({
  CamundaBpmAutoConfiguration.class,
  SpringProcessEngineServicesConfiguration.class
})
@ConditionalOnOAuth2ClientRegistrationProperties
@EnableConfigurationProperties(OAuth2Properties.class)
@Configuration
public class CadenzaFlowSpringSecurityOAuth2AutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(CadenzaFlowSpringSecurityOAuth2AutoConfiguration.class);
  private final OAuth2Properties oAuth2Properties;
  private final String webappPath;

  public CadenzaFlowSpringSecurityOAuth2AutoConfiguration(
      CamundaBpmProperties properties, OAuth2Properties oAuth2Properties) {
    this.oAuth2Properties = oAuth2Properties;
    WebappProperty webapp = properties.getWebapp();
    this.webappPath = webapp.getApplicationPath();
  }

  @Bean
  public FilterRegistrationBean<Filter> webappAuthenticationFilter() {
    FilterRegistrationBean<Filter> filterRegistration = new FilterRegistrationBean<>();
    filterRegistration.setName("Container Based Authentication Filter");
    filterRegistration.setFilter(new ContainerBasedAuthenticationFilter());
    filterRegistration.setInitParameters(
        Map.of(
            ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM,
            OAuth2AuthenticationProvider.class.getName()));
    // make sure the filter is registered after the Spring Security Filter Chain
    filterRegistration.setOrder(201);
    filterRegistration.addUrlPatterns(webappPath + "/app/*", webappPath + "/api/*");
    filterRegistration.setDispatcherTypes(DispatcherType.REQUEST);
    return filterRegistration;
  }

  @Bean
  @ConditionalOnProperty(
      name = "sso-logout.enabled",
      havingValue = "true",
      prefix = OAuth2Properties.PREFIX)
  protected SsoLogoutSuccessHandler ssoLogoutSuccessHandler(
      ClientRegistrationRepository clientRegistrationRepository) {
    log.debug("Registering SsoLogoutSuccessHandler");
    return new SsoLogoutSuccessHandler(clientRegistrationRepository, oAuth2Properties);
  }

  @Bean
  protected AuthorizeTokenFilter authorizeTokenFilter(OAuth2AuthorizedClientManager clientManager) {
    log.debug("Registering AuthorizeTokenFilter");
    return new AuthorizeTokenFilter(clientManager);
  }

  /**
   * The single configured registration id, if there is exactly one.
   *
   * <p>With one registration there is nothing to choose, yet Spring's generated
   * "Login with OAuth 2.0" page still appears on an error round-trip - e.g. pressing
   * Back onto an already redeemed authorization code lands on {@code /login?error} and
   * renders a misleading "Invalid credentials" alongside the provider's registration
   * id. Pointing the login page straight at the provider makes that case simply
   * re-authenticate. With several registrations the selection page is meaningful and
   * is therefore left alone.</p>
   */
  private static Optional<String> soleRegistrationId(ClientRegistrationRepository repository) {
    if (repository instanceof Iterable<?> registrations) {
      List<String> ids = new ArrayList<>();
      registrations.forEach(registration -> {
        if (registration instanceof ClientRegistration clientRegistration) {
          ids.add(clientRegistration.getRegistrationId());
        }
      });
      if (ids.size() == 1) {
        return Optional.of(ids.get(0));
      }
    }
    return Optional.empty();
  }

  // CSRF is deliberately disabled here (java:S4502): this chain only fronts the engine
  // web UIs, which run their own CsrfPreventionFilter inside the webapp filter chain,
  // and every other endpoint is a stateless JWT resource server.
  @SuppressWarnings("java:S4502")
  @Bean
  @Order(1)
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      AuthorizeTokenFilter authorizeTokenFilter,
      ClientRegistrationRepository clientRegistrationRepository,
      @Nullable SsoLogoutSuccessHandler ssoLogoutSuccessHandler) {

    log.info("Enabling Camunda Spring Security oauth2 integration");

    // Builder that uses PathPatternParser.defaultInstance and treats patterns
    // as relative to the context path (recommended)
    var pp = PathPatternRequestMatcher.withDefaults();

    RequestMatcher appEndpoints =
        new OrRequestMatcher(
            pp.matcher("/app/**"),
            pp.matcher("/api/**"),
            pp.matcher("/oauth2/**"),
            pp.matcher("/login/**"),
            pp.matcher("/logout"),
            pp.matcher("/lib/**"),
            pp.matcher("/assets/**"),
            pp.matcher("/favicon.ico"));

    http.securityMatcher(appEndpoints)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .addFilterAfter(authorizeTokenFilter, OAuth2AuthorizationRequestRedirectFilter.class)
        .anonymous(AbstractHttpConfigurer::disable)
        .oidcLogout(c -> c.backChannel(withDefaults()))
        .oauth2Login(login -> soleRegistrationId(clientRegistrationRepository)
            .ifPresent(id -> login.loginPage("/oauth2/authorization/" + id)))
        .logout(logout -> logout.clearAuthentication(true).invalidateHttpSession(true))
        .oauth2Client(withDefaults())
        .cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable);

    if (oAuth2Properties.getSsoLogout().isEnabled()) {
      http.logout(c -> c.logoutSuccessHandler(ssoLogoutSuccessHandler));
    }

    return http.build();
  }
}
