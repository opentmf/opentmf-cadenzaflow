package org.opentmf.cadenzaflow.config.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.ProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The real-database contract of {@link JobPriorityIndexPlugin} on PostgreSQL (the pattern
 * donor is {@code IncidentGroupQueryPlanProbeIT}): a fresh first boot creates
 * {@code act_idx_job_priority} present and valid; a second boot is a no-op that does not
 * rebuild it; an {@code indisvalid=false} leftover from an interrupted concurrent build is
 * dropped and rebuilt; and two engines booting together - the {@code camunda} +
 * {@code camunda-jobs} deployment pair - serialize on the advisory lock and end with
 * exactly one valid index. Each scenario runs in its own schema on one shared container.
 */
class JobPriorityIndexPluginIT {

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startPostgres() {
    postgres = new PostgreSQLContainer<>("postgres:18.0-alpine");
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void freshBootCreatesAValidIndexAndASecondBootLeavesItAlone() throws SQLException {
    createSchema("fresh_boot");

    bootAndClose("fresh_boot", "fresh-1");
    IndexState afterFirst = indexState("fresh_boot").orElseThrow();
    assertThat(afterFirst.valid()).isTrue();

    bootAndClose("fresh_boot", "fresh-2");
    IndexState afterSecond = indexState("fresh_boot").orElseThrow();
    assertThat(afterSecond.valid()).isTrue();
    assertThat(afterSecond.oid())
        .as("a no-op boot must not rebuild the index")
        .isEqualTo(afterFirst.oid());
  }

  @Test
  void anInvalidLeftoverFromAnInterruptedBuildIsRepaired() throws SQLException {
    createSchema("repair_case");
    bootAndClose("repair_case", "repair-1");
    IndexState planted = indexState("repair_case").orElseThrow();

    // what an interrupted CREATE INDEX CONCURRENTLY leaves behind
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update pg_index set indisvalid = false where indexrelid = ?")) {
      statement.setLong(1, planted.oid());
      statement.executeUpdate();
    }

    bootAndClose("repair_case", "repair-2");

    IndexState repaired = indexState("repair_case").orElseThrow();
    assertThat(repaired.valid()).isTrue();
    assertThat(repaired.oid())
        .as("the invalid leftover must be dropped and rebuilt, not kept")
        .isNotEqualTo(planted.oid());
  }

  @Test
  void twoEnginesBootingTogetherEndWithExactlyOneValidIndex() throws Exception {
    // Seed the engine tables with a first boot, then remove the index: the race under
    // test is the index step of two replicas booting on an existing database - the
    // engine's own property bootstrap on an EMPTY database has races of its own that
    // are not this plugin's subject.
    createSchema("race_case");
    bootAndClose("race_case", "race-seed");
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop index race_case.act_idx_job_priority");
    }

    CompletableFuture<ProcessEngine> first =
        CompletableFuture.supplyAsync(() -> bootEngine("race_case", "race-1"));
    CompletableFuture<ProcessEngine> second =
        CompletableFuture.supplyAsync(() -> bootEngine("race_case", "race-2"));
    ProcessEngine engineOne = first.join();
    ProcessEngine engineTwo = second.join();
    try {
      assertThat(engineOne).isNotNull();
      assertThat(engineTwo).isNotNull();
    } finally {
      engineOne.close();
      engineTwo.close();
    }

    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select count(*)
                  from pg_index i
                  join pg_class ci on ci.oid = i.indexrelid
                  join pg_class ct on ct.oid = i.indrelid
                  join pg_namespace n on n.oid = ct.relnamespace
                 where ci.relname = ? and ct.relname = ? and n.nspname = ?
                """)) {
      statement.setString(1, JobPriorityIndexPlugin.INDEX_NAME);
      statement.setString(2, JobPriorityIndexPlugin.TABLE_NAME);
      statement.setString(3, "race_case");
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getLong(1)).isEqualTo(1);
      }
    }
    assertThat(indexState("race_case").orElseThrow().valid()).isTrue();
  }

  // -------------------------------------------------------------------------
  // Support
  // -------------------------------------------------------------------------

  private static ProcessEngine bootEngine(String schema, String engineName) {
    StandaloneProcessEngineConfiguration configuration =
        new StandaloneProcessEngineConfiguration();
    configuration.setProcessEngineName(engineName);
    // The engine's schema CREATE script uses unqualified table names and lands in the
    // connection's current schema, while its runtime SQL uses the configured prefix -
    // the two only agree when the connection's currentSchema is the configured schema.
    configuration.setJdbcUrl(jdbcUrlWithCurrentSchema(schema));
    configuration.setJdbcUsername(postgres.getUsername());
    configuration.setJdbcPassword(postgres.getPassword());
    configuration.setJdbcDriver("org.postgresql.Driver");
    configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
    configuration.setDatabaseSchema(schema);
    configuration.setDatabaseTablePrefix(schema + ".");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEnginePlugins(List.of(new JobPriorityIndexPlugin()));
    return configuration.buildProcessEngine();
  }

  private static String jdbcUrlWithCurrentSchema(String schema) {
    String base = postgres.getJdbcUrl();
    return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private static void bootAndClose(String schema, String engineName) {
    ProcessEngine engine = bootEngine(schema, engineName);
    engine.close();
  }

  private static void createSchema(String schema) throws SQLException {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private record IndexState(long oid, boolean valid) {}

  private static Optional<IndexState> indexState(String schema) throws SQLException {
    try (Connection connection = adminConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select ci.oid, i.indisvalid
                  from pg_index i
                  join pg_class ci on ci.oid = i.indexrelid
                  join pg_class ct on ct.oid = i.indrelid
                  join pg_namespace n on n.oid = ct.relnamespace
                 where ci.relname = ? and ct.relname = ? and n.nspname = ?
                """)) {
      statement.setString(1, JobPriorityIndexPlugin.INDEX_NAME);
      statement.setString(2, JobPriorityIndexPlugin.TABLE_NAME);
      statement.setString(3, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? Optional.of(new IndexState(resultSet.getLong(1), resultSet.getBoolean(2)))
            : Optional.empty();
      }
    }
  }
}
