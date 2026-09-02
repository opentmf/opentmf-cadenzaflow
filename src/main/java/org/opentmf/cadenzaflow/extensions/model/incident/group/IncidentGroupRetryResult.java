package org.opentmf.cadenzaflow.extensions.model.incident.group;

import java.util.List;

/**
 * How many incidents matched the selector and the engine batches now working on them.
 * Poll batch progress via the stock {@code GET /engine-rest/batch/{id}} and
 * {@code GET /engine-rest/history/batch/{id}}.
 *
 * @author Cezmi Aslan
 */
public record IncidentGroupRetryResult(long incidentCount, List<BatchDescriptor> batches) {

  /** One created engine batch: its id, engine batch type, and how many ids it carries. */
  public record BatchDescriptor(String id, String type, int size) {}
}
