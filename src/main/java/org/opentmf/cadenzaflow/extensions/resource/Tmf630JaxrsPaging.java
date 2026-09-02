package org.opentmf.cadenzaflow.extensions.resource;

import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.opentmf.cadenzaflow.extensions.config.Tmf630PagingProperties;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.opentmf.query.tmf630.paging.TmfSortParser;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.opentmf.query.tmf630.util.Tmf630Util;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The TMF-630 paging contract, adapted to JAX-RS by calling the toolkit core's public
 * API programmatically: {@link Tmf630Util} decides 200/206/416 and writes
 * {@code X-Total-Count}/{@code X-Result-Count}/{@code Content-Range}, and
 * {@link TmfSortParser} parses the {@code -field,other} sort grammar against the
 * configured allowlist. Only the toolkit's Spring-MVC sugar (the {@code @Tmf630Response}
 * annotation, argument resolvers, exception advice) is replaced here — the semantics
 * stay the toolkit's own code, so this service pages like every other TMF-630 service
 * in the estate.
 *
 * <p>Offset/limit string handling mirrors the toolkit's (package-private) parser:
 * defaults from settings, {@code limit} clamped to {@code max-limit}, and in strict
 * mode a non-numeric value is a 400 instead of being ignored. Errors are rethrown as
 * the engine REST application's {@link InvalidRequestException} so error bodies look
 * like every other {@code /engine-rest} error.</p>
 *
 * @author Cezmi Aslan
 */
@Component
public class Tmf630JaxrsPaging {

  private final Tmf630PagingSettings settings;
  private final TmfSortParser sortParser;

  public Tmf630JaxrsPaging(Tmf630PagingProperties properties) {
    this.settings = properties.toSettings();
    this.sortParser = new TmfSortParser(
        settings.sortAllowlist(), settings.allowNestedSortProperties(), settings.nullsLast());
  }

  /** Query parameters → {@code Pageable}; 400 (engine-style) on anything invalid. */
  public Pageable pageable(String offsetRaw, String limitRaw, List<String> sortParams) {
    try {
      return new OffsetLimitPageRequest(
          parseOffset(offsetRaw), parseLimit(limitRaw), sortParser.parse(sortParams));
    } catch (TmfPagingException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * A page → the TMF-630 response: status, range headers, pagination {@code Link}
   * header (first/prev/next/last, derived from the request URI), and — when
   * {@code fields} is given — the field-selected body.
   */
  public <T> Response respond(Page<T> page, String fields, URI requestUri) {
    ResponseEntity<?> tmf;
    try {
      tmf = fields == null || fields.isBlank()
          ? Tmf630Util.tmfPage(page)
          : Tmf630Util.tmfPage(page, fields);
    } catch (RequestedRangeNotSatisfiableException e) {
      return Response.status(416)
          .header("X-Total-Count", String.valueOf(e.getTotalElements()))
          .header("Content-Range", "items */" + e.getTotalElements())
          .entity(Map.of(
              "type", "RequestedRangeNotSatisfiableException",
              "message", e.getMessage()))
          .build();
    }

    Response.ResponseBuilder response =
        Response.status(tmf.getStatusCode().value()).entity(tmf.getBody());
    tmf.getHeaders().forEach((name, values) -> values.forEach(v -> response.header(name, v)));
    if (!tmf.getHeaders().containsHeader(HttpHeaders.LINK)) {
      // Tmf630Util reads the request URI from Spring MVC's RequestContextHolder,
      // which a Jersey-dispatched request does not reliably populate - supply it.
      HttpHeaders link = new HttpHeaders();
      Tmf630Util.applyLinkHeader(link, requestUri.toString(), page.getTotalElements(),
          page.getPageable().getOffset(), page.getSize());
      link.forEach((name, values) -> values.forEach(v -> response.header(name, v)));
    }
    return response.build();
  }

  /** The sort to apply, with the TMF default of newest-first when none was asked for. */
  public Sort sortOrDefault(Pageable pageable) {
    return pageable.getSort().isUnsorted()
        ? Sort.by(Sort.Direction.DESC, "incidentTimestamp")
        : pageable.getSort();
  }

  private long parseOffset(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    try {
      long value = Long.parseLong(raw);
      if (value < 0) {
        throw new TmfPagingException("offset must be >= 0");
      }
      return value;
    } catch (NumberFormatException e) {
      if (settings.strictMode()) {
        throw new TmfPagingException("offset must be numeric", e);
      }
      return 0L;
    }
  }

  private int parseLimit(String raw) {
    int limit = settings.defaultLimit();
    if (raw != null && !raw.isBlank()) {
      try {
        int value = Integer.parseInt(raw);
        if (value <= 0) {
          throw new TmfPagingException("limit must be > 0");
        }
        limit = value;
      } catch (NumberFormatException e) {
        if (settings.strictMode()) {
          throw new TmfPagingException("limit must be numeric", e);
        }
      }
    }
    return Math.min(limit, settings.maxLimit());
  }
}
