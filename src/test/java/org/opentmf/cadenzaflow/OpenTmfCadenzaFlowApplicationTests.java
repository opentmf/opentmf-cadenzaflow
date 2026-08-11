package org.opentmf.cadenzaflow;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class OpenTmfCadenzaFlowApplicationTests {

  @Test
  void mainDelegatesToSpringApplicationRun() {
    try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
      OpenTmfCadenzaFlowApplication.main(new String[] {"--spring.main.web-application-type=none"});
      springApplication.verify(
          () ->
              SpringApplication.run(
                  OpenTmfCadenzaFlowApplication.class, "--spring.main.web-application-type=none"));
    }
  }
}
