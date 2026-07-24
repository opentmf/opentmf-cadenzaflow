package org.opentmf.cadenzaflow.config.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class SsoLogoutSuccessHandlerTest {

  @Test
  void redirectsToDefaultTargetUrlForNonOidcAuthentication() throws ServletException, IOException {
    SsoLogoutSuccessHandler handler =
        new SsoLogoutSuccessHandler(mock(ClientRegistrationRepository.class), new OAuth2Properties());
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    Authentication authentication = mock(Authentication.class);
    when(authentication.getName()).thenReturn("gokhan");

    handler.onLogoutSuccess(request, response, authentication);

    assertEquals("/", response.getRedirectedUrl());
  }
}
