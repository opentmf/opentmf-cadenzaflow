package org.opentmf.cadenzaflow.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.Date;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

/**
 * @author Cezmi Aslan
 */
class EngineRestDateUtilTests {

  @Test
  void absentMeansNull() {
    assertThat(EngineRestDateUtil.parse("p", null)).isNull();
    assertThat(EngineRestDateUtil.parse("p", "")).isNull();
    assertThat(EngineRestDateUtil.parse("p", "   ")).isNull();
  }

  @Test
  void acceptsIsoOffsetForm() {
    assertThat(EngineRestDateUtil.parse("p", "2026-09-01T07:58:41Z"))
        .isEqualTo(Date.from(Instant.parse("2026-09-01T07:58:41Z")));
    assertThat(EngineRestDateUtil.parse("p", "2026-09-01T09:58:41+02:00"))
        .isEqualTo(Date.from(Instant.parse("2026-09-01T07:58:41Z")));
  }

  @Test
  void acceptsTheEngineRestFormat() {
    assertThat(EngineRestDateUtil.parse("p", "2026-09-01T07:58:41.902+0000"))
        .isEqualTo(Date.from(Instant.parse("2026-09-01T07:58:41.902Z")));
  }

  @Test
  void refusesAnOutOfRangeEngineFormatValueInsteadOfRollingItOver() {
    // A lenient SimpleDateFormat would read month 13 as January of the next year.
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> EngineRestDateUtil.parse("p", "2026-13-01T07:58:41.000+0000"));
  }

  @Test
  void refusesGarbageNamingTheParameter() {
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> EngineRestDateUtil.parse("incidentTimestampAfter", "yesterday"))
        .withMessageContaining("incidentTimestampAfter");
  }
}
