package org.opentmf.cadenzaflow.extensions.model.incident.group;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Date;
import org.opentmf.cadenzaflow.extensions.util.EngineRestDateUtil;

/**
 * A group selector (as returned by the report's {@code selector} field) plus the retry
 * count to give every incident of that group. {@code retries} is an absolute SET, not
 * an increment — deliberate: set is idempotent under duplicated requests, the engine
 * batch API offers no atomic increment, and incident-matched tasks have zero retries
 * by definition, so the two semantics only differ on races, where set converges.
 *
 * <p>The optional time window ({@code incidentTimestampAfter} inclusive,
 * {@code incidentTimestampBefore} exclusive — the same half-open contract as the
 * queries) narrows the retry to the slice a filtered report displayed; the report
 * echoes its query window into each selector precisely so it can be posted back
 * verbatim. Kept as strings so both accepted timestamp formats round-trip; parsed on
 * demand via the {@code *Date()} accessors.</p>
 *
 * @author Cezmi Aslan
 */
public record IncidentGroupRetryRequest(
    @NotBlank String rootProcessDefinitionKey,
    @NotBlank String processDefinitionKey,
    @NotBlank String activityId,
    @NotBlank @Pattern(regexp = "failedJob|failedExternalTask") String incidentType,
    String tenantId,
    Integer processDefinitionVersion,
    String incidentTimestampAfter,
    String incidentTimestampBefore,
    @NotNull @Min(1) Integer retries) {

  /** Null when unbounded; engine-style 400 when unparseable. */
  public Date incidentTimestampAfterDate() {
    return EngineRestDateUtil.parse("incidentTimestampAfter", incidentTimestampAfter);
  }

  /** Null when unbounded; engine-style 400 when unparseable. */
  public Date incidentTimestampBeforeDate() {
    return EngineRestDateUtil.parse("incidentTimestampBefore", incidentTimestampBefore);
  }
}
