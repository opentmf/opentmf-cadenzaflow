package org.opentmf.cadenzaflow.extensions.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Serves the service's published API document — {@code docs/openapi.yaml}, packaged
 * into the jar at build time — at {@code /engine-rest/extensions/openapi.yaml}. Under
 * {@code /extensions} for the same reason as the incident operations: the path is this
 * service's addition and can never collide with a route the upstream engine grows. As
 * a GET under {@code /engine-rest/**} it is covered by that GET rule
 * ({@code reader}/{@code writer}/{@code admin}).
 *
 * <p>Read once at construction: the document is immutable for the life of the jar, and
 * a jar built without it fails at boot rather than on the first request.</p>
 *
 * @author Gokhan Demir
 */
@Component
@Path("/extensions/openapi.yaml")
public class OpenApiResource {

  static final String LOCATION = "openapi/openapi.yaml";

  /** RFC 9512. */
  static final String APPLICATION_YAML = "application/yaml";

  private final byte[] document;

  public OpenApiResource() {
    this(new ClassPathResource(LOCATION));
  }

  OpenApiResource(ClassPathResource resource) {
    try (InputStream in = resource.getInputStream()) {
      this.document = in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("API document not on the classpath: " + LOCATION, e);
    }
  }

  @GET
  @Produces(APPLICATION_YAML + ";charset=utf-8")
  public byte[] document() {
    return document;
  }
}
