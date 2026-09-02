package org.opentmf.cadenzaflow.extensions.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroup;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRepository;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupRollup;
import org.opentmf.cadenzaflow.extensions.util.EngineRestDateUtil;
import org.springframework.stereotype.Component;

/**
 * The grouped incident report — the aggregate VIEW of the extensions' incident
 * collection. Mounted INSIDE the engine's REST application so it shares its base
 * path, its Jackson configuration (engine date format) and — the point of the
 * placement — the existing {@code /engine-rest/**} RBAC rules. Registered by
 * {@code JerseyConfig}; a JAX-RS resource, not a Spring MVC controller, because
 * Jersey owns {@code /engine-rest/*}.
 *
 * <p>These paths are this service's addition, not part of the upstream engine
 * contract — namespaced under {@code /extensions} precisely so they can never
 * collide with (or shadow) anything the upstream contract has or grows. The family
 * is symmetric: {@code GET /extensions/incident} is the collection,
 * {@code GET /extensions/incident/groups} this aggregate view, and
 * {@code POST /extensions/incident/retry} the action ({@link IncidentRetryResource}).</p>
 *
 * @author Cezmi Aslan
 */
@Component
@Path("/extensions/incident/groups")
@Produces(MediaType.APPLICATION_JSON)
public class IncidentGroupResource {

  private final IncidentGroupRepository repository;
  private final IncidentGroupRollup rollup;

  public IncidentGroupResource(IncidentGroupRepository repository, IncidentGroupRollup rollup) {
    this.repository = repository;
    this.rollup = rollup;
  }

  /**
   * The timestamp range is HALF-OPEN ({@code incidentTimestampAfter} inclusive,
   * {@code incidentTimestampBefore} exclusive) on the originating incident's raise
   * time - "what broke since the 14:00 deploy" includes 14:00:00.000 exactly, and
   * consecutive windows chain without gaps. Formats per {@link EngineRestDateUtil}.
   */
  @GET
  public List<IncidentGroup> groups(
      @QueryParam("rootProcessDefinitionKey") String rootProcessDefinitionKey,
      @QueryParam("incidentType") String incidentType,
      @QueryParam("tenantId") String tenantId,
      @QueryParam("incidentTimestampAfter") String incidentTimestampAfter,
      @QueryParam("incidentTimestampBefore") String incidentTimestampBefore,
      @QueryParam("minIncidents") Integer minIncidents) {
    if (rootProcessDefinitionKey == null || rootProcessDefinitionKey.isBlank()) {
      throw new InvalidRequestException(Status.BAD_REQUEST,
          "Query parameter 'rootProcessDefinitionKey' is required");
    }
    return rollup.rollUp(
        repository.groups(
            rootProcessDefinitionKey, incidentType, tenantId,
            EngineRestDateUtil.parse("incidentTimestampAfter", incidentTimestampAfter),
            EngineRestDateUtil.parse("incidentTimestampBefore", incidentTimestampBefore),
            minIncidents),
        // Echoed verbatim into every selector, so a retry posted from this filtered
        // view is scoped to the same window.
        incidentTimestampAfter, incidentTimestampBefore);
  }
}
