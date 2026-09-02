package org.opentmf.cadenzaflow.extensions.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentListFilter;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.opentmf.cadenzaflow.extensions.service.IncidentListService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Direct calls with mocked collaborators: parameter shaping (comma lists, the two
 * accepted timestamp formats, href base path) is what lives in the resource and is
 * pinned here; the toolkit semantics have their own tests and the HTTP behaviour is
 * asserted end-to-end in IncidentOperationsIT.
 *
 * @author Cezmi Aslan
 */
class IncidentListResourceTests {

  private IncidentListService service;
  private Tmf630JaxrsPaging paging;
  private UriInfo uriInfo;
  private IncidentListResource resource;

  private final Pageable pageable = Pageable.ofSize(50);
  private final Sort sort = Sort.by("incidentTimestamp");
  private final Page<IncidentRow> page = new PageImpl<>(List.of());
  private final Response response = Response.ok().build();

  @BeforeEach
  void setUp() {
    service = mock(IncidentListService.class);
    paging = mock(Tmf630JaxrsPaging.class);
    uriInfo = mock(UriInfo.class);
    when(uriInfo.getBaseUri())
        .thenReturn(URI.create("http://host/cadenzaflow/v1/engine-rest/"));
    when(uriInfo.getRequestUri())
        .thenReturn(URI.create("http://host/cadenzaflow/v1/engine-rest/extensions/incident"));
    when(paging.pageable(any(), any(), any())).thenReturn(pageable);
    when(paging.sortOrDefault(pageable)).thenReturn(sort);
    when(service.page(any(), eq(pageable), eq(sort), anyString())).thenReturn(page);
    when(paging.respond(eq(page), any(), any())).thenReturn(response);
    resource = new IncidentListResource(service, paging);
  }

  private Response list(String keyIn, String after, String before) {
    return resource.list(uriInfo, "failedExternalTask", keyIn, null, null, null,
        "callWms", null, null, after, before, null, null, null,
        "0", "2", List.of("-incidentTimestamp"), "id,activityName");
  }

  @Test
  void shapesTheFilterAndDelegates() {
    assertThat(list("childFlow, otherFlow", null, null)).isSameAs(response);

    ArgumentCaptor<IncidentListFilter> filter =
        ArgumentCaptor.forClass(IncidentListFilter.class);
    verify(service).page(filter.capture(), eq(pageable), eq(sort),
        eq("/cadenzaflow/v1/engine-rest/"));
    assertThat(filter.getValue().incidentType()).isEqualTo("failedExternalTask");
    assertThat(filter.getValue().processDefinitionKeyIn())
        .as("comma lists are split and trimmed")
        .containsExactly("childFlow", "otherFlow");
    assertThat(filter.getValue().activityId()).isEqualTo("callWms");
    assertThat(filter.getValue().tenantIdIn())
        .as("absent comma-list parameters arrive as empty, which the service treats as no filter")
        .isEmpty();
    verify(paging).respond(page, "id,activityName", uriInfo.getRequestUri());

    resource.list(uriInfo, null, "a,, b,", null, null, null, null, null, null,
        null, null, null, null, null, null, null, List.of(), null);
    verify(service, org.mockito.Mockito.times(2)).page(filter.capture(), any(), any(), anyString());
    assertThat(filter.getValue().processDefinitionKeyIn())
        .as("empty comma segments are dropped, not forwarded")
        .containsExactly("a", "b");
  }

  @Test
  void acceptsIsoAndEngineDateFormats() {
    list(null, "2026-09-01T07:58:41Z", "2026-09-01T09:58:41.902+0000");

    ArgumentCaptor<IncidentListFilter> filter =
        ArgumentCaptor.forClass(IncidentListFilter.class);
    verify(service).page(filter.capture(), any(), any(), anyString());
    assertThat(filter.getValue().incidentTimestampAfter())
        .isEqualTo(Date.from(Instant.parse("2026-09-01T07:58:41Z")));
    assertThat(filter.getValue().incidentTimestampBefore())
        .isEqualTo(Date.from(Instant.parse("2026-09-01T09:58:41.902Z")));
  }

  @Test
  void refusesAnUnparseableTimestampNamingTheParameter() {
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> list(null, "yesterday", null))
        .withMessageContaining("incidentTimestampAfter");
  }
}
