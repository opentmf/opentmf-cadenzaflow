package org.opentmf.cadenzaflow.config.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Sends an unauthenticated webapp request to the shared page's Cockpit sign-in route
 * ({@code cadenzaflow.sso.login-page-url}, CF-SHARED-CALLBACK-3) with
 * {@code returnTo=<requested path and query>}, instead of starting the server-side OAuth2
 * login. The page signs the user in in the browser, posts the access token to
 * {@code POST /app/login-with-token} and then sends the browser to {@code returnTo}, which
 * it checks against its allowlist.
 *
 * @author Cezmi Aslan
 */
public class LoginPageAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final String loginPageUrl;

  public LoginPageAuthenticationEntryPoint(String loginPageUrl) {
    this.loginPageUrl = loginPageUrl;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response,
      AuthenticationException authException) throws IOException {
    String returnTo = request.getQueryString() == null
        ? request.getRequestURI()
        : request.getRequestURI() + "?" + request.getQueryString();
    // Encoded as a whole: a returnTo carrying its own query must not split into this one's
    // parameters. The configured URL has no query of its own (validated at boot).
    response.sendRedirect(
        loginPageUrl + "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
  }
}
