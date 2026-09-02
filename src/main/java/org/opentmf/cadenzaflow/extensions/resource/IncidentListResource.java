package org.opentmf.cadenzaflow.extensions.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Arrays;
import java.util.List;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.opentmf.cadenzaflow.extensions.service.IncidentListService;
import org.opentmf.cadenzaflow.extensions.util.EngineRestDateUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * A pageable, sortable incident list with the TMF-630 paging contract
 * ({@code offset}/{@code limit}, {@code X-Total-Count}, {@code Link}, 200/206/416,
 * {@code fields=} selection) over the engine's stock incident query. Mounted inside
 * the engine REST application like {@link IncidentGroupResource} and for the same
 * reasons (shared base path, RBAC, Jackson), namespaced under {@code /extensions}
 * to stay clear of the upstream contract. The path IS the collection —
 * {@code GET /extensions/incident} lists it, mirroring the stock
 * {@code /engine-rest/incident} collection with the estate's paging contract.
 *
 * <p>The timestamp range is HALF-OPEN - {@code incidentTimestampAfter} inclusive,
 * {@code incidentTimestampBefore} exclusive - so consecutive windows chain without
 * gaps or overlaps. This deliberately diverges from the stock {@code /incident}
 * endpoint, whose {@code after} is exclusive; the service adapts by querying the
 * stock filter with {@code after - 1ms} (exact, because the engine writes
 * millisecond-precision timestamps). Formats per {@link EngineRestDateUtil}.</p>
 *
 * @author Cezmi Aslan
 */
@Component
@Path("/extensions/incident")
@Produces(MediaType.APPLICATION_JSON)
public class IncidentListResource {

  private final IncidentListService service;
  private final Tmf630JaxrsPaging paging;

  public IncidentListResource(IncidentListService service, Tmf630JaxrsPaging paging) {
    this.service = service;
    this.paging = paging;
  }

  @GET
  @SuppressWarnings("java:S107") // one parameter per query parameter is the JAX-RS shape
  public Response list(
      @Context UriInfo uriInfo,
      @QueryParam("incidentType") String incidentType,
      @QueryParam("processDefinitionKeyIn") String processDefinitionKeyIn,
      @QueryParam("processDefinitionId") String processDefinitionId,
      @QueryParam("processInstanceId") String processInstanceId,
      @QueryParam("executionId") String executionId,
      @QueryParam("activityId") String activityId,
      @QueryParam("failedActivityId") String failedActivityId,
      @QueryParam("incidentMessageLike") String incidentMessageLike,
      @QueryParam("incidentTimestampAfter") String incidentTimestampAfter,
      @QueryParam("incidentTimestampBefore") String incidentTimestampBefore,
      @QueryParam("tenantIdIn") String tenantIdIn,
      @QueryParam("jobDefinitionIdIn") String jobDefinitionIdIn,
      @QueryParam("rootCauseIncidentId") String rootCauseIncidentId,
      @QueryParam("offset") String offset,
      @QueryParam("limit") String limit,
      @QueryParam("sort") List<String> sort,
      @QueryParam("fields") String fields) {
    IncidentListFilter filter = new IncidentListFilter(
        incidentType,
        commaList(processDefinitionKeyIn),
        processDefinitionId,
        processInstanceId,
        executionId,
        activityId,
        failedActivityId,
        incidentMessageLike,
        EngineRestDateUtil.parse("incidentTimestampAfter", incidentTimestampAfter),
        EngineRestDateUtil.parse("incidentTimestampBefore", incidentTimestampBefore),
        commaList(tenantIdIn),
        commaList(jobDefinitionIdIn),
        rootCauseIncidentId);
    Pageable pageable = paging.pageable(offset, limit, sort);

    Page<IncidentRow> page = service.page(
        filter, pageable, paging.sortOrDefault(pageable), uriInfo.getBaseUri().getPath());
    return paging.respond(page, fields, uriInfo.getRequestUri());
  }

  private static List<String> commaList(String raw) {
    // The blank check is a fast path only: without it, split/trim/filter produces the
    // same empty list (which is why PIT reports its removal as an equivalent mutant).
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

}
