package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class LoginPageAuthenticationEntryPointTests {

  private final LoginPageAuthenticationEntryPoint entryPoint =
      new LoginPageAuthenticationEntryPoint("https://ui.example.test/auth/cockpit");

  @Test
  void redirectsToTheLoginPageWithTheRequestedPath() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/cadenzaflow/v1/app/cockpit/");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, new InsufficientAuthenticationException("none"));

    assertEquals(302, response.getStatus());
    assertEquals(
        "https://ui.example.test/auth/cockpit?returnTo=%2Fcadenzaflow%2Fv1%2Fapp%2Fcockpit%2F",
        response.getRedirectedUrl());
  }

  @Test
  void aQueryOfTheRequestTravelsInsideReturnTo() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/cadenzaflow/v1/app/cockpit/default/");
    request.setQueryString("a=1&b=2");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, new InsufficientAuthenticationException("none"));

    assertEquals(
        "https://ui.example.test/auth/cockpit"
            + "?returnTo=%2Fcadenzaflow%2Fv1%2Fapp%2Fcockpit%2Fdefault%2F%3Fa%3D1%26b%3D2",
        response.getRedirectedUrl());
  }
}
