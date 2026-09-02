package org.opentmf.cadenzaflow.extensions.util;

import jakarta.ws.rs.core.Response.Status;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.cadenzaflow.bpm.engine.rest.mapper.JacksonConfigurator;

/**
 * Timestamp query parameters for the incident operations endpoints, accepted in
 * ISO-8601 offset form ({@code 2026-09-01T07:58:41Z} or {@code +01:00}) and in the
 * engine REST date format ({@code yyyy-MM-dd'T'HH:mm:ss.SSSZ}) — so estate clients
 * and engine-native clients can both reuse values they already have, including
 * timestamps read verbatim from these endpoints' own responses.
 *
 * @author Cezmi Aslan
 */
public final class EngineRestDateUtil {

  private EngineRestDateUtil() {}

  /** Null for an absent parameter; engine-style 400 for an unparseable one. */
  public static Date parse(String parameter, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Date.from(OffsetDateTime.parse(raw).toInstant());
    } catch (DateTimeParseException _) {
      // Fall through to the engine REST date format.
    }
    try {
      return new SimpleDateFormat(JacksonConfigurator.DEFAULT_DATE_FORMAT).parse(raw);
    } catch (ParseException _) {
      throw new InvalidRequestException(Status.BAD_REQUEST,
          parameter + " must be an ISO-8601 offset date-time or use the engine date"
              + " format " + JacksonConfigurator.DEFAULT_DATE_FORMAT + ": " + raw);
    }
  }
}
