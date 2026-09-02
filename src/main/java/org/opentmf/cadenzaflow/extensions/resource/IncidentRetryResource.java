package org.opentmf.cadenzaflow.extensions.resource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.stream.Collectors;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryRequest;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroupRetryResult;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupRetryService;
import org.springframework.stereotype.Component;

/**
 * The retry ACTION on the extensions' incident collection: retries the set of
 * incidents matching the posted selector — taken verbatim from a report group's
 * {@code selector} field, optionally narrowed by definition version, tenant, or the
 * half-open time window a filtered report echoed into it. The path is
 * {@code /extensions/incident/retry} (not {@code .../groups/retry}) because the
 * operation acts on INCIDENTS by filter; a "group" is a report projection with no
 * identity of its own. The mandatory group-key fields in the body are a deliberate
 * blast-radius bound, enforced by validation, not by the path.
 *
 * <p>An empty match answers {@code {"incidentCount": 0, "batches": []}} — someone
 * else retried first, which is an outcome, not an error. Bean Validation is invoked
 * explicitly: the engine's Jersey application has no validation feature wired.</p>
 *
 * @author Cezmi Aslan
 */
@Component
@Path("/extensions/incident/retry")
@Produces(MediaType.APPLICATION_JSON)
public class IncidentRetryResource {

  private final IncidentGroupRetryService retryService;
  private final Validator validator;

  public IncidentRetryResource(IncidentGroupRetryService retryService, Validator validator) {
    this.retryService = retryService;
    this.validator = validator;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public IncidentGroupRetryResult retry(IncidentGroupRetryRequest request) {
    if (request == null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "A request body is required");
    }
    var violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw new InvalidRequestException(Status.BAD_REQUEST, violations.stream()
          .map(IncidentRetryResource::describe)
          .sorted()
          .collect(Collectors.joining("; ")));
    }
    // Force the window parse here so an unparseable timestamp is a 400 before any
    // engine interaction (the accessors throw the engine-style InvalidRequestException).
    request.incidentTimestampAfterDate();
    request.incidentTimestampBeforeDate();
    return retryService.retry(request);
  }

  private static String describe(ConstraintViolation<IncidentGroupRetryRequest> violation) {
    return violation.getPropertyPath() + " " + violation.getMessage();
  }
}
