package org.opentmf.cadenzaflow.extensions.model.incident.group;

import jakarta.validation.constraints.NotBlank;

/**
 * Where the failing process was called from: the parent definition and the call
 * activity in it. Null on the group itself when the incident sits in the root BPMN.
 *
 * <p>Part of the group identity, so part of the {@link IncidentGroupSelector}: the same
 * child BPMN called from two different call activities under one root is two groups,
 * and a retry posted from one must not cross into the other.</p>
 *
 * @author Cezmi Aslan
 */
public record CalledFrom(@NotBlank String processDefinitionKey, @NotBlank String callActivityId) {}
