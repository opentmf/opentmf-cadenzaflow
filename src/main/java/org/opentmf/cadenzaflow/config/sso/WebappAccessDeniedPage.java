package org.opentmf.cadenzaflow.config.sso;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.util.HtmlUtils;

/**
 * The no-access page of the engine webapps (ENGINE-COCKPIT-FORBIDDEN-PAGE-1).
 *
 * <p>A user who is signed in but holds no ACCESS grant on a webapp is refused by the engine
 * webapp's own {@code SecurityFilter} with {@code sendError(403, "No access rights for
 * cockpit")}. The container dispatches that to Boot's {@code /error}, whose default
 * rendering is the Whitelabel Error Page. This resolver takes over exactly that case - a
 * 403 whose failed request was under the webapps' {@code /app/} path - and answers a plain
 * HTML page: the application's name, the group(s) that may open it (the engine's
 * administrator groups, i.e. {@code administrator-group-name}), and the sign-out link to
 * the chain's {@code /logout}. Every other status and path returns {@code null}, which
 * keeps Boot's default page. (Declaring an {@link ErrorViewResolver} bean replaces Boot's
 * convention-based {@code DefaultErrorViewResolver}; this project ships no
 * {@code error/4xx} templates or static error pages, so nothing is lost.)</p>
 *
 * @author Gokhan Demir
 */
public class WebappAccessDeniedPage implements ErrorViewResolver {

  private static final Map<String, String> APP_TITLES = Map.of(
      "cockpit", "Cockpit", "admin", "Admin", "tasklist", "Tasklist", "welcome", "Welcome");

  private static final String PAGE = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>No access</title>
      <style>
      body { font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 36rem; \
      padding: 0 1rem; color: #222; }
      h1 { font-size: 1.5rem; }
      a { color: #0a58ca; }
      </style>
      </head>
      <body>
      <h1>No access to %s</h1>
      <p>%s</p>
      <p><a href="%s">Sign out</a> and sign in with an account that has it.</p>
      </body>
      </html>
      """;

  private final String appsPrefix;
  private final Supplier<List<String>> adminGroups;

  /**
   * @param webappPath the webapps' mount path ({@code cadenzaflow.bpm.webapp.application-path},
   *     {@code /} here), context-relative
   * @param adminGroups the engine's administrator groups, read when the page is rendered -
   *     the identity plugins add the configured group only after the engine is built
   */
  public WebappAccessDeniedPage(String webappPath, Supplier<List<String>> adminGroups) {
    this.appsPrefix = ("/".equals(webappPath) ? "" : webappPath) + "/app/";
    this.adminGroups = adminGroups;
  }

  @Override
  public ModelAndView resolveErrorView(
      HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
    if (status != HttpStatus.FORBIDDEN || application(request) == null) {
      return null;
    }
    String html = html(request);
    View view = (viewModel, req, response) -> {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.TEXT_HTML_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(html);
    };
    return new ModelAndView(view);
  }

  /** The page for the failed request named by the error dispatch's attributes. */
  String html(HttpServletRequest request) {
    String application = application(request);
    String title = APP_TITLES.getOrDefault(application, HtmlUtils.htmlEscape(application));
    List<String> groups = adminGroups.get();
    String sentence = groups.isEmpty()
        ? "Your account has no access grant on the " + title
            + " application. Ask an administrator for one."
        : "Your account is in none of the groups that may open " + title + ": "
            + HtmlUtils.htmlEscape(String.join(", ", groups)) + ".";
    return PAGE.formatted(title, sentence, request.getContextPath() + "/logout");
  }

  /** The webapp named by the failed request's path, or null when it was not a webapp. */
  private String application(HttpServletRequest request) {
    Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    if (!(uri instanceof String requestUri)) {
      return null;
    }
    String contextPath = request.getContextPath();
    if (!requestUri.startsWith(contextPath + appsPrefix)) {
      return null;
    }
    String rest = requestUri.substring(contextPath.length() + appsPrefix.length());
    int slash = rest.indexOf('/');
    String application = slash < 0 ? rest : rest.substring(0, slash);
    return application.isEmpty() ? null : application;
  }
}
