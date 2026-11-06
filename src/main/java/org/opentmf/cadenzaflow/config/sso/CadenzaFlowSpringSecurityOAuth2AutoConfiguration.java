package org.opentmf.cadenzaflow.config.sso;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import jakarta.annotation.Nullable;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cadenzaflow.bpm.engine.spring.SpringProcessEngineServicesConfiguration;
import org.cadenzaflow.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cadenzaflow.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cadenzaflow.bpm.spring.boot.starter.property.WebappProperty;
import org.cadenzaflow.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.opentmf.security.config.ServletJwtSupport;
import org.opentmf.security.model.OpenTmfSecurityProperties;
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
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
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
@EnableConfigurationProperties({OAuth2Properties.class, SsoProperties.class})
@Configuration
public class CadenzaFlowSpringSecurityOAuth2AutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(CadenzaFlowSpringSecurityOAuth2AutoConfiguration.class);
  private final OAuth2Properties oAuth2Properties;
  private final String sharedCallbackUrl;
  private final String signInCallbackUrl;
  private final String loginPageUrl;
  private final Duration bearerSessionMax;
  private final boolean userTokenOnly;
  private final String bearerLoginUserClaim;
  private final String webappPath;

  public CadenzaFlowSpringSecurityOAuth2AutoConfiguration(
      CamundaBpmProperties properties, OAuth2Properties oAuth2Properties,
      SsoProperties ssoProperties) {
    this.oAuth2Properties = oAuth2Properties;
    // Validated here, at boot, rather than on the first sign-in.
    this.sharedCallbackUrl = ssoProperties.sharedCallback().orElse(null);
    this.signInCallbackUrl = ssoProperties.sharedCallbackSignInUrl().orElse(null);
    this.loginPageUrl = ssoProperties.loginPage().orElse(null);
    this.bearerSessionMax = ssoProperties.getBearerSessionMax();
    this.userTokenOnly = ssoProperties.getBearerLogin().isUserTokenOnly();
    this.bearerLoginUserClaim = ssoProperties.getBearerLogin().userClaimOverride().orElse(null);
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

  /**
   * ENGINE-COCKPIT-FORBIDDEN-PAGE-1: a signed-in user without an ACCESS grant on a webapp
   * sees this page, not Spring's Whitelabel 403. The administrator groups are read at
   * render time: the identity plugin adds the configured one after the engine is built.
   */
  @Bean
  public WebappAccessDeniedPage webappAccessDeniedPage(
      ProcessEngineConfigurationImpl engineConfiguration) {
    return new WebappAccessDeniedPage(webappPath,
        () -> Objects.requireNonNullElse(engineConfiguration.getAdminGroups(), List.of()));
  }

  @Bean
  @ConditionalOnProperty(
      name = "sso-logout.enabled",
      havingValue = "true",
      prefix = OAuth2Properties.PREFIX)
  protected SsoLogoutSuccessHandler ssoLogoutSuccessHandler(
      ClientRegistrationRepository clientRegistrationRepository) {
    log.debug("Registering SsoLogoutSuccessHandler");
    return new SsoLogoutSuccessHandler(
        clientRegistrationRepository, oAuth2Properties, sharedCallbackUrl);
  }

  /**
   * Spring's resolver for {@code /oauth2/authorization/{id}}, with PKCE on every request
   * and, when signing in through the shared callback, its URL and the {@code cf.} state.
   */
  private OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      ClientRegistrationRepository clientRegistrationRepository) {
    DefaultOAuth2AuthorizationRequestResolver resolver =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    resolver.setAuthorizationRequestCustomizer(
        new SharedCallbackAuthorizationRequestCustomizer(signInCallbackUrl));
    if (signInCallbackUrl != null) {
      log.info("Webapp sign-in and sign-out use the shared callback {}", signInCallbackUrl);
    } else if (sharedCallbackUrl != null) {
      log.info("Webapp sign-out returns to the shared callback {}", sharedCallbackUrl);
    }
    return resolver;
  }

  @Bean
  protected AuthorizeTokenFilter authorizeTokenFilter(OAuth2AuthorizedClientManager clientManager) {
    log.debug("Registering AuthorizeTokenFilter");
    return new AuthorizeTokenFilter(clientManager, bearerSessionMax);
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
        .oauth2Login(login -> {
          soleRegistrationId(clientRegistrationRepository)
              .ifPresent(id -> login.loginPage("/oauth2/authorization/" + id));
          login.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
              authorizationRequestResolver(clientRegistrationRepository)));
        })
        .logout(logout -> logout.clearAuthentication(true).invalidateHttpSession(true))
        .oauth2Client(withDefaults())
        .cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable);

    if (oAuth2Properties.getSsoLogout().isEnabled()) {
      http.logout(c -> c.logoutSuccessHandler(ssoLogoutSuccessHandler));
    }
    if (loginPageUrl != null) {
      // CF-SHARED-CALLBACK-3: the shared page signs the user in, not the server-side login.
      log.info("Unauthenticated webapp requests go to the login page {}", loginPageUrl);
      http.exceptionHandling(handling -> handling.authenticationEntryPoint(
          new LoginPageAuthenticationEntryPoint(loginPageUrl)));
    }

    return http.build();
  }

  /**
   * {@code POST /app/login-with-token} (CF-SHARED-CALLBACK-3), ahead of the webapp chain.
   *
   * <p>With {@code cadenzaflow.sso.login-page-url} set, the bearer token is validated by
   * the very {@link ServletJwtSupport} that configures {@code /engine-rest}'s resource
   * server: same decoder(s), issuer, audience and expiry checks, same user and authorities
   * claims. A refused or missing token is a 401 and nothing is stored. A valid one reaches
   * {@link LoginWithTokenFilter}, which opens a new webapp session. Unset, the path answers
   * 404: the endpoint exists only for the shared page's login.</p>
   *
   * <p>CSRF protection is off on purpose (java:S4502): the credential is the
   * {@code Authorization} header, which a cross-site page cannot send, and the filter also
   * refuses another {@code Origin}.</p>
   */
  @SuppressWarnings("java:S4502")
  @Bean
  @Order(0)
  public SecurityFilterChain loginWithTokenFilterChain(
      HttpSecurity http, ServletJwtSupport servletJwtSupport, JwtDecoder jwtDecoder,
      OpenTmfSecurityProperties openTmfSecurityProperties) {
    http.securityMatcher(PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, LoginWithTokenFilter.PATH))
        .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
        .cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable);
    if (loginPageUrl == null) {
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .addFilterAfter((request, response, chain) ->
              ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND),
              AuthorizationFilter.class);
    } else {
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
          .oauth2ResourceServer(servletJwtSupport::apply)
          .addFilterAfter(new LoginWithTokenFilter(jwtDecoder,
                  userTokenOnly
                      ? new UserTokenPolicy(openTmfSecurityProperties, bearerLoginUserClaim)
                      : null,
                  bearerLoginUserClaim),
              AuthorizationFilter.class);
    }
    return http.build();
  }
}
