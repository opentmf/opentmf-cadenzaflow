package org.opentmf.cadenzaflow.extensions.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.config.Tmf630PagingProperties;
import org.opentmf.cadenzaflow.extensions.model.incident.list.IncidentRow;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * @author Cezmi Aslan
 */
class Tmf630JaxrsPagingTests {

  private static final URI REQUEST_URI =
      URI.create("http://host/cadenzaflow/v1/engine-rest/extensions/incident?limit=2");

  private Tmf630JaxrsPaging paging;

  @BeforeEach
  void setUp() {
    Tmf630PagingProperties properties = new Tmf630PagingProperties();
    properties.setSortAllowlist(List.of("incidentTimestamp", "activityId"));
    paging = new Tmf630JaxrsPaging(properties);
  }

  private static Page<String> page(List<String> content, long offset, int limit, long total) {
    return new PageImpl<>(content,
        new OffsetLimitPageRequest(offset, limit, Sort.unsorted()), total);
  }

  @Test
  void defaultsApplyWhenOffsetAndLimitAreAbsent() {
    Pageable pageable = paging.pageable(null, null, List.of());
    assertThat(pageable.getOffset()).isZero();
    assertThat(pageable.getPageSize()).isEqualTo(50);
    // Blank counts as absent, exactly like null.
    Pageable blank = paging.pageable(" ", "", List.of());
    assertThat(blank.getOffset()).isZero();
    assertThat(blank.getPageSize()).isEqualTo(50);
  }

  @Test
  void givenOffsetAndLimitAreUsedVerbatim() {
    Pageable pageable = paging.pageable("7", "20", List.of());
    assertThat(pageable.getOffset()).isEqualTo(7L);
    assertThat(pageable.getPageSize()).isEqualTo(20);
  }

  @Test
  void limitIsClampedToTheConfiguredMaximum() {
    assertThat(paging.pageable(null, "9999", List.of()).getPageSize()).isEqualTo(500);
  }

  @Test
  void strictModeRefusesNonNumericAndNegativeValues() {
    List<String> noSort = List.of();
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> paging.pageable("abc", null, noSort))
        .withMessageContaining("offset");
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> paging.pageable("-1", null, noSort));
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> paging.pageable(null, "0", noSort))
        .withMessageContaining("limit");
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> paging.pageable(null, "abc", noSort))
        .withMessageContaining("limit");
  }

  @Test
  void sortGrammarAndAllowlistComeFromTheToolkitParser() {
    Pageable pageable = paging.pageable("0", "10",
        List.of("-incidentTimestamp,activityId"));
    assertThat(pageable.getSort().getOrderFor("incidentTimestamp").getDirection())
        .isEqualTo(Sort.Direction.DESC);
    assertThat(pageable.getSort().getOrderFor("activityId").getDirection())
        .isEqualTo(Sort.Direction.ASC);

    List<String> bogus = List.of("bogus");
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> paging.pageable("0", "10", bogus))
        .withMessageContaining("bogus");
  }

  @Test
  void defaultSortIsNewestFirstOnlyWhenNoneWasAsked() {
    Pageable unsorted = paging.pageable(null, null, List.of());
    assertThat(paging.sortOrDefault(unsorted))
        .isEqualTo(Sort.by(Sort.Direction.DESC, "incidentTimestamp"));

    Pageable sorted = paging.pageable(null, null, List.of("activityId"));
    assertThat(paging.sortOrDefault(sorted)).isEqualTo(sorted.getSort());
  }

  @Test
  void aPartialPageAnswers206WithRangeAndLinkHeaders() {
    Response response = paging.respond(page(List.of("a", "b"), 0, 2, 3), null, REQUEST_URI);

    assertThat(response.getStatus()).isEqualTo(206);
    assertThat(response.getHeaderString("X-Total-Count")).isEqualTo("3");
    assertThat(response.getHeaderString("X-Result-Count")).isEqualTo("2");
    assertThat(response.getHeaderString("Content-Range")).isEqualTo("items 1-2/3");
    assertThat(response.getHeaderString("Link"))
        .contains("rel=\"first\"")
        .contains("offset=2>; rel=\"next\"")
        .contains("rel=\"last\"")
        .doesNotContain("rel=\"prev\"");
    assertThat(response.getEntity()).isEqualTo(List.of("a", "b"));
  }

  @Test
  void aCompletePageAnswers200() {
    Response response = paging.respond(page(List.of("a"), 0, 50, 1), null, REQUEST_URI);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeaderString("Content-Range")).isEqualTo("items 1-1/1");
  }

  @Test
  void anOffsetBeyondTheTotalAnswers416() {
    Response response = paging.respond(page(List.of(), 10, 2, 3), null, REQUEST_URI);
    assertThat(response.getStatus()).isEqualTo(416);
    assertThat(response.getHeaderString("X-Total-Count")).isEqualTo("3");
    assertThat(response.getHeaderString("Content-Range")).isEqualTo("items */3");
  }

  @Test
  void anEmptyResultIsA200NotA416() {
    Response response = paging.respond(page(List.of(), 0, 50, 0), null, REQUEST_URI);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeaderString("X-Total-Count")).isEqualTo("0");
  }

  @Test
  void blankFieldsMeansNoSelectionAndTheRawBody() {
    Page<String> page = page(List.of("a"), 0, 50, 1);
    Response response = paging.respond(page, "  ", REQUEST_URI);
    assertThat(response.getEntity())
        .as("a blank fields parameter must not trigger field selection")
        .isEqualTo(List.of("a"));
  }

  @Test
  void fieldSelectionNarrowsTheBodyThroughTheToolkit() {
    // The REAL row type on purpose: the toolkit reads record components reflectively,
    // which needs the record class to be public - a fact worth pinning against
    // IncidentRow itself rather than a test-local stand-in.
    IncidentRow row = new IncidentRow("i-1", "/engine-rest/incident/i-1",
        "failedExternalTask", null, "boom", "callWms", "Call WMS", null, null, null,
        null, null, null, null, null, "i-1", true, null, null, null, null);
    Page<IncidentRow> page = new PageImpl<>(List.of(row),
        new OffsetLimitPageRequest(0, 50, Sort.unsorted()), 1);

    Response response = paging.respond(page, "id,activityName", REQUEST_URI);

    assertThat(response.getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> body =
        (List<java.util.Map<String, Object>>) response.getEntity();
    assertThat(body).hasSize(1);
    assertThat(body.get(0))
        .containsEntry("id", "i-1")
        .containsEntry("activityName", "Call WMS")
        .doesNotContainKey("incidentMessage")
        .doesNotContainKey("configuration");
  }
}
