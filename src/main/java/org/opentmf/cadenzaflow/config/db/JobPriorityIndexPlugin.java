package org.opentmf.cadenzaflow.config.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.SchemaOperationsCommand;
import org.cadenzaflow.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Creates {@code act_idx_job_priority ON act_ru_job (priority_ DESC, duedate_)} at engine
 * boot, unconditionally on PostgreSQL — the one index priority-ordered job acquisition
 * ({@code jobExecutorAcquireByPriority}) needs and the engine's stock schema does not ship.
 * It is created here, by the service, so a fresh install gets it on the very first boot
 * instead of depending on a deploy-side init job that can only land it once the engine has
 * created {@code act_ru_job}.
 *
 * <p>Why unconditional: the index costs nothing on the executor's hot path — lock/unlock
 * updates leave {@code priority_}/{@code duedate_} values unchanged, so PostgreSQL keeps
 * them HOT (measured: 97.9% HOT with or without the index). Only duedate-changing
 * retry-backoff updates lose HOT eligibility — the failure path, not the steady state —
 * and one schema everywhere removes the drift class where the acquisition flag and the
 * index disagree per environment.</p>
 *
 * <p>Two concerns ride the one plugin because they share the same advisory-lock key:</p>
 *
 * <ul>
 *   <li>{@link #preInit}: wraps the engine's schema-operations command so that concurrent
 *       first boots (the {@code camunda} and {@code camunda-jobs} deployments) serialize
 *       their {@code DbSchemaUpdate} on a {@code pg_advisory_xact_lock} — the engine takes
 *       no lock of its own around schema creation.</li>
 *   <li>{@link #postProcessEngineBuild}: runs after {@code DbSchemaUpdate} has created
 *       {@code act_ru_job}, on its own autocommit connection ({@code CREATE INDEX
 *       CONCURRENTLY} refuses a transaction block), under {@code pg_advisory_lock} on the
 *       same key. An {@code indisvalid=false} leftover from an interrupted concurrent
 *       build is dropped first, because such an index satisfies {@code IF NOT EXISTS} and
 *       would otherwise be kept silently.</li>
 * </ul>
 *
 * <p>The index is an optimization, never a boot gate: any failure is a WARN naming the
 * index, schema and SQLState, and the engine boots on. Non-PostgreSQL databases (the H2
 * test runs) are skipped with an INFO.</p>
 *
 * @author Cezmi Aslan
 */
@Configuration
public class JobPriorityIndexPlugin extends AbstractProcessEnginePlugin {

  private static final Logger log = LoggerFactory.getLogger(JobPriorityIndexPlugin.class);

  /** One shared key ("CF_DDL_1") serializes schema create and index DDL across replicas. */
  static final long SCHEMA_DDL_LOCK_KEY = 0x43465F44444C5F31L;

  static final String INDEX_NAME = "act_idx_job_priority";
  static final String TABLE_NAME = "act_ru_job";

  /** Matches the identifier shape we will interpolate into DDL; anything else is skipped. */
  private static final String PLAIN_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";

  private static final String VALIDITY_SQL =
      """
      select i.indisvalid
        from pg_index i
        join pg_class ci on ci.oid = i.indexrelid
        join pg_class ct on ct.oid = i.indrelid
        join pg_namespace n on n.oid = ct.relnamespace
       where ci.relname = ? and ct.relname = ?
         and n.nspname = coalesce(?, current_schema())
      """;

  @Override
  public void preInit(ProcessEngineConfigurationImpl engineConfig) {
    engineConfig.setSchemaOperationsCommand(
        new AdvisoryLockedSchemaOperations(engineConfig.getSchemaOperationsCommand()));
  }

  @Override
  public void postProcessEngineBuild(ProcessEngine processEngine) {
    ProcessEngineConfigurationImpl engineConfig =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    if (!DbSqlSessionFactory.POSTGRES.equals(engineConfig.getDatabaseType())) {
      log.info(
          "Skipping {} creation: database type '{}' is not PostgreSQL",
          INDEX_NAME,
          engineConfig.getDatabaseType());
      return;
    }
    String schema = resolveSchema(engineConfig);
    try (Connection connection = engineConfig.getDataSource().getConnection()) {
      connection.setAutoCommit(true);
      acquireAdvisoryLock(connection);
      try {
        ensureIndex(connection, schema);
      } finally {
        releaseAdvisoryLock(connection);
      }
    } catch (SQLException | RuntimeException e) {
      log.warn(
          "Could not ensure index {} on {}.{} (SQLState {}): {} - priority-ordered job"
              + " acquisition will sort without index support until the index is created",
          INDEX_NAME,
          schema == null ? "<current_schema>" : schema,
          TABLE_NAME,
          e instanceof SQLException sqlException ? sqlException.getSQLState() : "n/a",
          e.getMessage());
    }
  }

  // S2077 (dynamically formatted SQL): DDL cannot bind parameters, so the statements are
  // assembled from INDEX_NAME/TABLE_NAME (compile-time constants) and the schema, which
  // resolveSchema accepts only when it matches PLAIN_IDENTIFIER ([A-Za-z_][A-Za-z0-9_]*)
  // and otherwise refuses to interpolate - pinned by the
  // aSchemaThatIsNotAPlainIdentifierIsNotInterpolatedIntoDdl unit test, whose input is
  // the injection-shaped "bad.name; drop table act_ru_job".
  @SuppressWarnings("java:S2077")
  private void ensureIndex(Connection connection, String schema) throws SQLException {
    Optional<Boolean> validity = indexValidity(connection, schema);
    if (validity.isPresent() && Boolean.FALSE.equals(validity.get())) {
      // An interrupted CREATE INDEX CONCURRENTLY leaves an INVALID index behind, and an
      // invalid index satisfies IF NOT EXISTS - so it must be dropped before the create.
      try (Statement statement = connection.createStatement()) {
        statement.execute("drop index " + qualified(schema, INDEX_NAME));
      }
      log.info(
          "Dropped invalid {} left behind by an interrupted concurrent build",
          qualified(schema, INDEX_NAME));
      validity = Optional.empty();
    }
    if (validity.isEmpty()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "create index concurrently if not exists "
                + INDEX_NAME
                + " on "
                + qualified(schema, TABLE_NAME)
                + " (priority_ desc, duedate_)");
      }
    }
    if (indexValidity(connection, schema).orElse(false)) {
      log.info("Index {} is present and valid", qualified(schema, INDEX_NAME));
    } else {
      log.warn(
          "Index {} is missing or invalid after creation attempt - priority-ordered job"
              + " acquisition will sort without index support",
          qualified(schema, INDEX_NAME));
    }
  }

  /** Empty when the index does not exist; otherwise its {@code pg_index.indisvalid}. */
  private Optional<Boolean> indexValidity(Connection connection, String schema)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(VALIDITY_SQL)) {
      statement.setString(1, INDEX_NAME);
      statement.setString(2, TABLE_NAME);
      statement.setString(3, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(resultSet.getBoolean(1)) : Optional.empty();
      }
    }
  }

  private void acquireAdvisoryLock(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select pg_advisory_lock(?)")) {
      statement.setLong(1, SCHEMA_DDL_LOCK_KEY);
      statement.execute();
    }
  }

  private void releaseAdvisoryLock(Connection connection) {
    try (PreparedStatement statement =
        connection.prepareStatement("select pg_advisory_unlock(?)")) {
      statement.setLong(1, SCHEMA_DDL_LOCK_KEY);
      statement.execute();
    } catch (SQLException e) {
      // The session-level lock dies with the connection the try-with-resources is about
      // to close, so a failed explicit unlock costs nothing.
      log.debug("Advisory unlock failed; the lock releases with the connection", e);
    }
  }

  /**
   * The engine's configured schema ({@code databaseSchema}, else the table prefix minus
   * its trailing dot), or {@code null} to address the connection's current schema. A name
   * that is not a plain identifier is not interpolated into DDL.
   */
  static String resolveSchema(ProcessEngineConfigurationImpl engineConfig) {
    String schema = engineConfig.getDatabaseSchema();
    if ((schema == null || schema.isBlank())
        && engineConfig.getDatabaseTablePrefix() != null
        && engineConfig.getDatabaseTablePrefix().endsWith(".")) {
      String prefix = engineConfig.getDatabaseTablePrefix();
      schema = prefix.substring(0, prefix.length() - 1);
    }
    if (schema == null || schema.isBlank()) {
      return null;
    }
    if (!schema.matches(PLAIN_IDENTIFIER)) {
      log.warn(
          "Configured schema '{}' is not a plain identifier; using the connection's"
              + " current schema for {}",
          schema,
          INDEX_NAME);
      return null;
    }
    return schema;
  }

  private static String qualified(String schema, String name) {
    return schema == null ? name : schema + "." + name;
  }

  /**
   * Serializes the engine's schema operations across replicas booting together: the stock
   * {@code SchemaOperationsProcessEngineBuild} runs {@code dbSchemaCreate/dbSchemaUpdate}
   * with no lock of any kind, so two engines on an empty database race the DDL. The
   * transaction-scoped advisory lock rides the command's own connection and releases
   * itself at commit or rollback.
   */
  static final class AdvisoryLockedSchemaOperations implements SchemaOperationsCommand {

    private final SchemaOperationsCommand delegate;

    AdvisoryLockedSchemaOperations(SchemaOperationsCommand delegate) {
      this.delegate = delegate;
    }

    SchemaOperationsCommand delegate() {
      return delegate;
    }

    @Override
    public Void execute(CommandContext commandContext) {
      ProcessEngineConfigurationImpl engineConfig =
          commandContext.getProcessEngineConfiguration();
      if (DbSqlSessionFactory.POSTGRES.equals(engineConfig.getDatabaseType())) {
        try {
          Connection connection =
              commandContext.getDbSqlSession().getSqlSession().getConnection();
          long waitStart = System.nanoTime();
          try (PreparedStatement statement =
              connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
            statement.setLong(1, SCHEMA_DDL_LOCK_KEY);
            statement.execute();
          }
          // Logged on every boot: a wait well above zero is the visible trace of a peer
          // replica holding the lock while it created or updated the schema.
          log.info(
              "schema DDL advisory lock acquired after {} ms",
              (System.nanoTime() - waitStart) / 1_000_000);
        } catch (SQLException | RuntimeException e) {
          log.warn(
              "Could not take the schema DDL advisory lock; schema operations proceed"
                  + " unserialized: {}",
              e.getMessage());
        }
        assertStatedSchema(commandContext, engineConfig);
      }
      return delegate.execute(commandContext);
    }

    /**
     * Fails the boot when the connection's {@code current_schema()} is not the stated
     * {@code databaseSchema}: with no table prefix, every unqualified statement - the
     * engine's own DDL first among them - lands in the current schema, so a missing or
     * wrong {@code currentSchema} on the JDBC URL must die here, BEFORE any DDL runs,
     * not as tables silently created in the wrong schema. Runs under the advisory lock
     * on the command's own connection; skipped when no schema is stated.
     */
    private void assertStatedSchema(
        CommandContext commandContext, ProcessEngineConfigurationImpl engineConfig) {
      String stated = engineConfig.getDatabaseSchema();
      if (stated == null || stated.isBlank()) {
        return;
      }
      String actual;
      try {
        Connection connection =
            commandContext.getDbSqlSession().getSqlSession().getConnection();
        try (PreparedStatement statement =
                connection.prepareStatement("select current_schema()");
            ResultSet resultSet = statement.executeQuery()) {
          actual = resultSet.next() ? resultSet.getString(1) : null;
        }
      } catch (SQLException e) {
        log.warn(
            "Could not read current_schema() to verify the stated schema '{}': {}",
            stated,
            e.getMessage());
        return;
      }
      if (stated.equals(actual)) {
        log.info(
            "Engine schema check: stated schema-name '{}' matches current_schema() '{}'",
            stated,
            actual);
      } else {
        throw new IllegalStateException(
            "Engine schema mismatch: schema-name '" + stated
                + "' but the connection's current_schema() is '" + actual
                + "' — put currentSchema=" + stated + " on the JDBC URL");
      }
    }
  }
}
