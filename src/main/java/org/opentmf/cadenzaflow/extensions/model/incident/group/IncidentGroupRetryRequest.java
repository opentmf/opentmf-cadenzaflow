package org.opentmf.cadenzaflow.extensions.model.incident.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * <p>{@code calledFrom} narrows the retry to incidents in instances called from that
 * parent definition's call activity. The report always echoes the group's own value, so
 * a selector posted back verbatim stays inside its group; a hand-written request may
 * omit it to retry the activity across every caller under the root.</p>
 *
 * <p>An omitted or null {@code tenantId} / {@code calledFrom} does not filter on that key (any
 * tenant, any caller). "None" is said explicitly: {@code withoutTenant: true} retries only
 * incidents without a tenant, {@code rootLevelOnly: true} only incidents in the root
 * instance itself (no caller). The report emits both markers on the groups they describe,
 * so a null-tenant or root-level group posted back verbatim stays inside its group
 * (CF-INCIDENT-SELECTOR-NULL-IS-NOT-UNSCOPED-1). A marker together with a value for the
 * same key is refused.</p>
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
    Boolean withoutTenant,
    @Valid CalledFrom calledFrom,
    Boolean rootLevelOnly,
    Integer processDefinitionVersion,
    String incidentTimestampAfter,
    String incidentTimestampBefore,
    @NotNull @Min(1) Integer retries) {

  /** A request without the two markers: tenant and caller filter only when given. */
  public IncidentGroupRetryRequest(String rootProcessDefinitionKey, String processDefinitionKey,
      String activityId, String incidentType, String tenantId, CalledFrom calledFrom,
      Integer processDefinitionVersion, String incidentTimestampAfter,
      String incidentTimestampBefore, Integer retries) {
    this(rootProcessDefinitionKey, processDefinitionKey, activityId, incidentType, tenantId,
        null, calledFrom, null, processDefinitionVersion, incidentTimestampAfter,
        incidentTimestampBefore, retries);
  }

  /** {@code withoutTenant: true} is the tenant scope; a tenantId beside it contradicts it. */
  @AssertTrue(message = "must not be combined with a tenantId")
  public boolean isWithoutTenantAlone() {
    return !Boolean.TRUE.equals(withoutTenant) || tenantId == null;
  }

  /** {@code rootLevelOnly: true} is the caller scope; a calledFrom beside it contradicts it. */
  @AssertTrue(message = "must not be combined with a calledFrom")
  public boolean isRootLevelOnlyAlone() {
    return !Boolean.TRUE.equals(rootLevelOnly) || calledFrom == null;
  }

  /** Null when unbounded; engine-style 400 when unparseable. */
  public Date incidentTimestampAfterDate() {
    return EngineRestDateUtil.parse("incidentTimestampAfter", incidentTimestampAfter);
  }

  /** Null when unbounded; engine-style 400 when unparseable. */
  public Date incidentTimestampBeforeDate() {
    return EngineRestDateUtil.parse("incidentTimestampBefore", incidentTimestampBefore);
  }
}
