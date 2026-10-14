package org.opentmf.cadenzaflow.config.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.ProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The CF-SCHEMA-FROM-URL-1 contract: the engine boots with its schema STATED once
 * ({@code databaseSchema} plus the prefix the engine insists on, {@code schema + '.'})
 * and REACHED through the JDBC URL's {@code currentSchema} - no connection-pool schema -
 * and every engine table lands in the stated schema, none in {@code public}. The engine
 * runs as a role named UNLIKE the schema, so Postgres's default {@code "$user"}
 * search_path cannot mask a missing currentSchema; on this Postgres major, {@code public}
 * is owner-only for CREATE, so the failing shape has nowhere to land silently either.
 * That failing shape - same role, no currentSchema on the URL - must die on the schema
 * guard BEFORE any DDL runs.
 */
class SchemaFromUrlBootIT {

  private static final String SCHEMA = "cadenzaflow";
  private static final String ROLE = "svc_runner";
  private static final String ROLE_PASSWORD = "svc";

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startPostgresAndCreateRoleAndSchema() throws SQLException {
    postgres = new PostgreSQLContainer<>("postgres:18.0-alpine");
    postgres.start();
    try (Connection connection = superuserConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create role " + ROLE + " login password '" + ROLE_PASSWORD + "'");
      statement.execute("create schema " + SCHEMA + " authorization " + ROLE);
    }
  }

  @AfterAll
  static void stopPostgres() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void statedSchemaReachedViaUrlCurrentSchemaLandsEveryEngineTableThere() throws SQLException {
    ProcessEngine engine = bootEngine(urlWithCurrentSchema(), "url-shape-1");
    try {
      assertThat(actTableCount(SCHEMA)).isPositive();
      assertThat(actTableCount("public")).isZero();
      assertThat(priorityIndexIsValid()).isTrue();
      // Prefix-qualified RUNTIME SQL resolves against the stated schema too: this query
      // renders as select ... from cadenzaflow.ACT_RE_DEPLOYMENT and would throw if the
      // derived prefix pointed anywhere the DDL did not land.
      assertThat(engine.getRepositoryService().createDeploymentQuery().count()).isZero();
    } finally {
      engine.close();
    }

    // Second boot: the presence check, filtered by the stated schema, sees the tables
    // and the whole schema phase is a no-op.
    bootEngine(urlWithCurrentSchema(), "url-shape-2").close();
    assertThat(actTableCount("public")).isZero();
  }

  @Test
  void aMissingCurrentSchemaFailsTheBootOnTheGuardBeforeAnyDdl() throws SQLException {
    assertThatThrownBy(() -> bootEngine(postgres.getJdbcUrl(), "guard-kill"))
        .hasStackTraceContaining("Engine schema mismatch")
        .hasStackTraceContaining("currentSchema=" + SCHEMA);
    assertThat(actTableCount("public")).isZero();
  }

  // -------------------------------------------------------------------------
  // Support
  // -------------------------------------------------------------------------

  private static ProcessEngine bootEngine(String jdbcUrl, String engineName) {
    StandaloneProcessEngineConfiguration configuration =
        new StandaloneProcessEngineConfiguration();
    configuration.setProcessEngineName(engineName);
    configuration.setJdbcUrl(jdbcUrl);
    configuration.setJdbcUsername(ROLE);
    configuration.setJdbcPassword(ROLE_PASSWORD);
    configuration.setJdbcDriver("org.postgresql.Driver");
    configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
    configuration.setDatabaseSchema(SCHEMA);
    // The engine refuses a schema without this exact prefix; the shipped config derives
    // both from the one stated property, and this IT mirrors that pair.
    configuration.setDatabaseTablePrefix(SCHEMA + ".");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEnginePlugins(List.of(new JobPriorityIndexPlugin()));
    return configuration.buildProcessEngine();
  }

  private static String urlWithCurrentSchema() {
    String base = postgres.getJdbcUrl();
    return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
  }

  private static Connection superuserConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static int actTableCount(String schema) throws SQLException {
    try (Connection connection = superuserConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from information_schema.tables"
                    + " where table_schema = ? and table_name like 'act\\_%' escape '\\'")) {
      statement.setString(1, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static boolean priorityIndexIsValid() throws SQLException {
    try (Connection connection = superuserConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select i.indisvalid
                  from pg_index i
                  join pg_class ci on ci.oid = i.indexrelid
                  join pg_namespace n on n.oid = ci.relnamespace
                 where ci.relname = ? and n.nspname = ?
                """)) {
      statement.setString(1, JobPriorityIndexPlugin.INDEX_NAME);
      statement.setString(2, SCHEMA);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1);
      }
    }
  }
}
