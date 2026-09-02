package org.opentmf.cadenzaflow.extensions.model.incident.group;

/**
 * Where the failing process was called from: the parent definition and the call
 * activity in it. Null on the group itself when the incident sits in the root BPMN.
 *
 * @author Cezmi Aslan
 */
public record CalledFrom(String processDefinitionKey, String callActivityId) {}
