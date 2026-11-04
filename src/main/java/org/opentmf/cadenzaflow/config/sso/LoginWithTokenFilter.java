package org.opentmf.cadenzaflow.config.sso;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.impl.util.ClockUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The end of {@code POST /app/login-with-token} (CF-SHARED-CALLBACK-3): turns a bearer
 * token, already validated by the resource-server filter in front of it, into a webapp
 * session.
 *
 * <p>The filter chain in front validates the {@code Authorization: Bearer} token exactly as
 * {@code /engine-rest} does: same decoder, issuer, audience, expiry and signature checks,
 * and the same user and authorities claims. A refused token never reaches this filter
 * (401, nothing stored). Here:</p>
 *
 * <ul>
 *   <li>a cross-origin request is refused with 403: {@code Sec-Fetch-Site}, when sent,
 *       must be {@code same-origin}, and {@code Origin}, when sent, must name this host;</li>
 *   <li>unless {@code bearer-login.user-token-only} is off, only a delegated user token is
 *       accepted ({@link UserTokenPolicy}); any other is refused with 401 and one WARN;</li>
 *   <li>an {@code X-ID-Token}, when sent, is validated by the same decoder and kept ONLY as
 *       the logout's {@code id_token_hint}. An invalid one refuses the login (401);</li>
 *   <li>any session the browser already had is invalidated and a NEW one is created
 *       (session-fixation protection), holding a {@link BearerSessionAuthentication} copied
 *       from the token's (same name and authorities). From then on
 *       it is an ordinary webapp session: the token's own expiry does not end it, the
 *       webapp's idle timeout and {@code cadenzaflow.sso.bearer-session-max} do;</li>
 *   <li>the answer is 204 with {@code Location} = the Cockpit app URL.</li>
 * </ul>
 *
 * <p>Terminal: the request is never passed on.</p>
 *
 * @author Cezmi Aslan
 */
public class LoginWithTokenFilter extends OncePerRequestFilter {

  /** Context-relative path of the endpoint. */
  public static final String PATH = "/app/login-with-token";

  /** Request header carrying the ID token for the logout's {@code id_token_hint}. */
  public static final String ID_TOKEN_HEADER = "X-ID-Token";

  private static final Logger log = LoggerFactory.getLogger(LoginWithTokenFilter.class);

  private final SecurityContextRepository sessionRepository =
      new HttpSessionSecurityContextRepository();
  private final JwtDecoder idTokenDecoder;
  private final UserTokenPolicy userTokenPolicy;
  private final String userClaim;

  /**
   * @param idTokenDecoder the decoder {@code /engine-rest} trusts (it routes by {@code iss}
   *     when several issuers are configured), for the optional {@code X-ID-Token}
   * @param userTokenPolicy refuses non-user tokens, or {@code null} to accept every token
   *     {@code /engine-rest} accepts ({@code bearer-login.user-token-only: false})
   */
  public LoginWithTokenFilter(
      JwtDecoder idTokenDecoder, @Nullable UserTokenPolicy userTokenPolicy) {
    this(idTokenDecoder, userTokenPolicy, null);
  }

  /**
   * @param userClaim {@code bearer-login.user-claim}: the claim that names the session's
   *     user, or {@code null} for the principal {@code /engine-rest} derives
   */
  public LoginWithTokenFilter(
      JwtDecoder idTokenDecoder, @Nullable UserTokenPolicy userTokenPolicy,
      @Nullable String userClaim) {
    this.idTokenDecoder = idTokenDecoder;
    this.userTokenPolicy = userTokenPolicy;
    this.userClaim = userClaim;
  }

  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!sameOrigin(request)) {
      if (log.isWarnEnabled()) {
        log.warn("Refused a token login for '{}': cross-origin (Origin '{}', Sec-Fetch-Site '{}')",
            token.getName(), request.getHeader("Origin"), request.getHeader("Sec-Fetch-Site"));
      }
      SecurityContextHolder.clearContext();
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    String principal = token.getName();
    String user = principal;
    if (userClaim != null) {
      user = token.getToken().getClaimAsString(userClaim);
      if (user == null || user.isBlank()) {
        log.warn("Refused a token login for '{}': the token has no '{}' claim"
            + " (cadenzaflow.sso.bearer-login.user-claim)", principal, userClaim);
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }
    if (userTokenPolicy != null) {
      Optional<String> refusal = userTokenPolicy.refusal(token.getToken(), user);
      if (refusal.isPresent()) {
        String reason = refusal.get();
        log.warn("Refused a token login for '{}': {}", user, reason);
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }
    String idToken = request.getHeader(ID_TOKEN_HEADER);
    if (idToken != null) {
      try {
        idTokenDecoder.decode(idToken);
      } catch (JwtException e) {
        log.warn("Refused a token login for '{}': invalid {}: {}",
            token.getName(), ID_TOKEN_HEADER, e.getMessage());
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }

    HttpSession previous = request.getSession(false);
    if (previous != null) {
      previous.invalidate();
    }
    request.getSession(true);
    // Stored as our own BearerSessionAuthentication: Spring's JwtAuthenticationToken is
    // @Transient, and the session repository silently refuses to store it.
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(BearerSessionAuthentication.of(
        token, user, new BearerSessionDetails(idToken, ClockUtil.now().toInstant())));
    SecurityContextHolder.setContext(context);
    sessionRepository.saveContext(context, request, response);

    log.info("Opened a webapp session for '{}' by token login", user);
    response.setHeader("Location", request.getContextPath() + "/app/cockpit/");
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /**
   * Same-origin by both browser signals, each checked only when sent (a non-browser caller
   * sends neither). For {@code Origin} the host alone is compared: behind a
   * TLS-terminating ingress the scheme and port the application sees differ from the
   * browser's.
   */
  static boolean sameOrigin(HttpServletRequest request) {
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null && !"same-origin".equalsIgnoreCase(fetchSite)) {
      return false;
    }
    String origin = request.getHeader("Origin");
    if (origin == null) {
      return true;
    }
    try {
      String host = URI.create(origin).getHost();
      return host != null && host.equalsIgnoreCase(request.getServerName());
    } catch (IllegalArgumentException _) {
      return false;
    }
  }
}
