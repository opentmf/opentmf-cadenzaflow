package org.opentmf.cadenzaflow.extensions.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.cadenzaflow.bpm.engine.rest.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.extensions.model.incident.group.IncidentGroup;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRepository;
import org.opentmf.cadenzaflow.extensions.repository.IncidentGroupRow;
import org.opentmf.cadenzaflow.extensions.service.IncidentGroupRollup;

/**
 * Direct calls with mocked collaborators; the HTTP semantics around them (status
 * codes, security, serialization) are asserted end-to-end in IncidentOperationsIT.
 *
 * @author Cezmi Aslan
 */
class IncidentGroupResourceTests {

  private IncidentGroupRepository repository;
  private IncidentGroupRollup rollup;
  private IncidentGroupResource resource;

  @BeforeEach
  void setUp() {
    repository = mock(IncidentGroupRepository.class);
    rollup = mock(IncidentGroupRollup.class);
    resource = new IncidentGroupResource(repository, rollup);
  }

  @Test
  void reportPipesTheRowsThroughTheRollup() {
    List<IncidentGroupRow> rows = List.of();
    List<IncidentGroup> groups = List.of();
    Date after = Date.from(Instant.parse("2026-09-01T14:00:00Z"));
    when(repository.groups("orderFulfilment", "failedJob", "tenant-1", after, null, 4))
        .thenReturn(rows);
    // The raw query-window strings must reach the rollup verbatim - that is what
    // the selector echo is built from.
    when(rollup.rollUp(rows, "2026-09-01T14:00:00Z", null)).thenReturn(groups);

    assertThat(resource.groups("orderFulfilment", "failedJob", "tenant-1",
        "2026-09-01T14:00:00Z", null, 4)).isSameAs(groups);
  }

  @Test
  void reportRefusesAnUnparseableTimestampNamingTheParameter() {
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.groups("orderFulfilment", null, null,
            "yesterday", null, null))
        .withMessageContaining("incidentTimestampAfter");
    verifyNoInteractions(repository);
  }

  @Test
  void reportRefusesAMissingOrBlankRootKeyAsABadRequest() {
    // Spring MVC would 400 a missing required parameter by itself; JAX-RS leaves the
    // parameter null, so the explicit check is what keeps the 400 contract.
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.groups(null, null, null, null, null, null))
        .withMessageContaining("rootProcessDefinitionKey");
    assertThatExceptionOfType(InvalidRequestException.class)
        .isThrownBy(() -> resource.groups(" ", null, null, null, null, null));
    verifyNoInteractions(repository);
  }
}
