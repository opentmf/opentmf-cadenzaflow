package org.opentmf.cadenzaflow.extensions.repository;

import java.util.regex.Pattern;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.springframework.stereotype.Component;

/**
 * Qualifies engine table names with the prefix the engine itself is configured with
 * ({@code cadenzaflow.bpm.database.table-prefix}, e.g. {@code cadenzaflow.}). Every
 * hand-written SQL statement against the {@code ACT_*} tables must go through
 * {@link #table(String)} — a bare table name only works when the prefix happens to
 * match the connection's default schema.
 *
 * <p>The prefix is validated once, at startup: it participates in SQL text (a schema
 * name cannot be a bound parameter), so anything but a plain optionally-dot-terminated
 * identifier is refused outright.</p>
 *
 * @author Cezmi Aslan
 */
@Component
public class EngineSqlSupport {

  private static final Pattern VALID_PREFIX = Pattern.compile("^\\w*\\.?$");

  private final String prefix;

  public EngineSqlSupport(ProcessEngine processEngine) {
    String configured = ((ProcessEngineConfigurationImpl) processEngine
        .getProcessEngineConfiguration())
        .getDatabaseTablePrefix();
    String candidate = configured == null ? "" : configured;
    if (!VALID_PREFIX.matcher(candidate).matches()) {
      throw new IllegalStateException(
          "Refusing the engine database table prefix '" + candidate
              + "': not a plain identifier, so it cannot be spliced into SQL text");
    }
    this.prefix = candidate;
  }

  /** The prefixed table name, e.g. {@code table("ACT_RU_INCIDENT")} → {@code cadenzaflow.ACT_RU_INCIDENT}. */
  public String table(String tableName) {
    return prefix + tableName;
  }
}
