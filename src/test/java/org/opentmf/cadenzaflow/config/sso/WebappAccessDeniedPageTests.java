package org.opentmf.cadenzaflow.config.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ENGINE-COCKPIT-FORBIDDEN-PAGE-1: a signed-in user without an ACCESS grant on a webapp is
 * refused by the engine webapp's own SecurityFilter with {@code sendError(403, "No access
 * rights for cockpit")}; the container then dispatches to Boot's {@code /error}. The page
 * must be ours - naming the group that may open the app and offering the sign-out - and
 * not Spring's Whitelabel page. Every other error keeps Boot's default rendering.
 */
class WebappAccessDeniedPageTests {

  private static final String CONTEXT = "/cadenzaflow/v1";

  private final WebappAccessDeniedPage page =
      new WebappAccessDeniedPage("/", () -> List.of("dnms-engine-admin"));

  /** Boot's error controller as the container's error dispatch reaches it. */
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
      new BasicErrorController(new DefaultErrorAttributes(), new ErrorProperties(), List.of(page)))
      .build();

  @Test
  void aSignedInUserWithoutTheCockpitRoleGetsTheNoAccessPageNotWhitelabel() throws Exception {
    mvc.perform(get(CONTEXT + "/error").contextPath(CONTEXT).accept(MediaType.TEXT_HTML)
            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)
            .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, CONTEXT + "/app/cockpit/default/")
            .requestAttr(RequestDispatcher.ERROR_MESSAGE, "No access rights for cockpit"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("<title>No access</title>")))
        .andExpect(content().string(containsString("Cockpit")))
        .andExpect(content().string(containsString("dnms-engine-admin")))
        .andExpect(content().string(containsString("href=\"" + CONTEXT + "/logout\"")))
        .andExpect(content().string(not(containsString("Whitelabel"))));
  }

  @Test
  void theAppNameComesFromThePathAndIsEscaped() {
    MockHttpServletRequest request = errorRequest(403, CONTEXT + "/app/<x>/default/");

    String body = page.html(request);

    assertThat(body).doesNotContain("<x>").contains("&lt;x&gt;");
  }

  @Test
  void withoutAConfiguredAdministratorGroupThePageAsksForAGrant() {
    WebappAccessDeniedPage noAdmins = new WebappAccessDeniedPage("/", List::of);

    String body = noAdmins.html(errorRequest(403, CONTEXT + "/app/admin/default/"));

    assertThat(body).contains("Admin").contains("access grant").doesNotContain("null");
  }

  @Test
  void otherErrorsAndOtherPathsKeepBootsDefaultPage() {
    assertThat(page.resolveErrorView(
        errorRequest(404, CONTEXT + "/app/cockpit/default/"), HttpStatus.NOT_FOUND, Map.of()))
        .isNull();
    assertThat(page.resolveErrorView(
        errorRequest(403, CONTEXT + "/engine-rest/task"), HttpStatus.FORBIDDEN, Map.of()))
        .isNull();
    assertThat(page.resolveErrorView(
        errorRequest(403, null), HttpStatus.FORBIDDEN, Map.of()))
        .isNull();
  }

  @Test
  void aWebappMountedUnderItsOwnPathIsRecognisedToo() {
    WebappAccessDeniedPage mounted =
        new WebappAccessDeniedPage("/camunda", () -> List.of("camunda-admin"));

    assertThat(mounted.resolveErrorView(
        errorRequest(403, CONTEXT + "/camunda/app/tasklist/default/"),
        HttpStatus.FORBIDDEN, Map.of()))
        .isNotNull();
    assertThat(mounted.resolveErrorView(
        errorRequest(403, CONTEXT + "/app/tasklist/default/"), HttpStatus.FORBIDDEN, Map.of()))
        .isNull();
  }

  private static MockHttpServletRequest errorRequest(int status, String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", CONTEXT + "/error");
    request.setContextPath(CONTEXT);
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    if (requestUri != null) {
      request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, requestUri);
    }
    return request;
  }
}
