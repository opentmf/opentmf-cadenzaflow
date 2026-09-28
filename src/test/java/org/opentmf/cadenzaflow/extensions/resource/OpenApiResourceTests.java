package org.opentmf.cadenzaflow.extensions.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author Gokhan Demir
 */
class OpenApiResourceTests {

  @Test
  void serveThePackagedApiDocument() {
    String document = new String(new OpenApiResource().document(), StandardCharsets.UTF_8);

    assertThat(document)
        .as("docs/openapi.yaml, packaged by the pom's resource entry")
        .startsWith("openapi: 3.2.0")
        .contains("/engine-rest/extensions/incident/groups:");
  }

  @Test
  void refuseToStartWithoutTheDocument() {
    ClassPathResource missing = new ClassPathResource("openapi/missing.yaml");

    assertThatExceptionOfType(UncheckedIOException.class)
        .isThrownBy(() -> new OpenApiResource(missing))
        .withMessageContaining(OpenApiResource.LOCATION);
  }
}
