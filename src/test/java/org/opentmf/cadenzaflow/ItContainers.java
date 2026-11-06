package org.opentmf.cadenzaflow;

import java.time.Duration;

/**
 * Shared settings for the Testcontainers the ITs start.
 *
 * <p>The startup timeout is generous on purpose: on a busy shared build machine a Keycloak
 * realm import has been measured at almost five minutes. A slow start should cost minutes,
 * not a red build. A container that never comes up still fails, after this timeout.</p>
 *
 * <p>The {@code jdbc:tc:} Postgres of the {@code it} profile is started by the Testcontainers
 * JDBC driver, which takes no startup-timeout parameter; it keeps the module's default.</p>
 */
public final class ItContainers {

  /** Startup timeout of every container an IT starts itself. */
  public static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

  private ItContainers() {
  }
}
