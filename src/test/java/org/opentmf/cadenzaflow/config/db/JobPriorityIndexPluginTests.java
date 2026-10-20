package org.opentmf.cadenzaflow.config.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.SchemaOperationsCommand;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cadenzaflow.bpm.engine.impl.db.sql.DbSqlSession;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/**
 * Unit coverage for {@link JobPriorityIndexPlugin}: the non-PostgreSQL skip (through a real
 * H2 engine boot, which also proves the schema-operations wrapper does not break a boot),
 * the JDBC sequences of the Postgres paths against mocked connections, the WARN-and-continue
 * failure contract, schema resolution, and the advisory-locked schema-operations wrapper.
 * The real-database behavior (fresh boot, repair, no-op, racing boots) is
 * {@code JobPriorityIndexPluginIT}'s job.
 */
class JobPriorityIndexPluginTests {

  private final JobPriorityIndexPlugin plugin = new JobPriorityIndexPlugin();

  // -------------------------------------------------------------------------
  // Non-PostgreSQL: skipped, and a real boot stays healthy with the plugin on
  // -------------------------------------------------------------------------

  @Test
  void h2EngineBootsWithThePluginAndSkipsTheIndex() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEnginePlugins(List.of(new JobPriorityIndexPlugin()));
    configuration.setJobExecutorActivate(false);
    configuration.setJdbcUrl("jdbc:h2:mem:job-priority-index-test;DB_CLOSE_DELAY=-1");

    ProcessEngine engine = configuration.buildProcessEngine();
    try {
      // the wrapper was installed by preInit and the whole boot ran through it
      assertThat(configuration.getSchemaOperationsCommand())
          .isInstanceOf(JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations.class);
    } finally {
      engine.close();
    }
  }

  @Test
  void nonPostgresSkipsWithoutTouchingTheDataSource() {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getDatabaseType()).thenReturn("h2");
    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getProcessEngineConfiguration()).thenReturn(configuration);

    plugin.postProcessEngineBuild(engine);

    verify(configuration, never()).getDataSource();
  }

  // -------------------------------------------------------------------------
  // PostgreSQL JDBC sequences (mocked connection)
  // -------------------------------------------------------------------------

  @Test
  void freshDatabaseCreatesTheIndexUnderTheAdvisoryLock() throws SQLException {
    PostgresFixture fixture = new PostgresFixture("cadenzaflow");
    fixture.indexValidityReturns(null, Boolean.TRUE); // absent before, valid after

    plugin.postProcessEngineBuild(fixture.engine);

    InOrder order = inOrder(fixture.connection, fixture.lockStatement, fixture.ddlStatement,
        fixture.unlockStatement);
    order.verify(fixture.connection).setAutoCommit(true);
    order.verify(fixture.lockStatement).execute();
    order.verify(fixture.ddlStatement)
        .execute(
            "create index concurrently if not exists act_idx_job_priority"
                + " on cadenzaflow.act_ru_job (priority_ desc, duedate_)");
    order.verify(fixture.unlockStatement).execute();
    verify(fixture.lockStatement).setLong(1, JobPriorityIndexPlugin.INDEX_DDL_LOCK_KEY);
  }

  @Test
  void invalidLeftoverIsDroppedBeforeTheCreate() throws SQLException {
    PostgresFixture fixture = new PostgresFixture("cadenzaflow");
    fixture.indexValidityReturns(Boolean.FALSE, Boolean.TRUE); // invalid before, valid after

    plugin.postProcessEngineBuild(fixture.engine);

    InOrder order = inOrder(fixture.ddlStatement);
    order.verify(fixture.ddlStatement).execute("drop index cadenzaflow.act_idx_job_priority");
    order.verify(fixture.ddlStatement)
        .execute(
            "create index concurrently if not exists act_idx_job_priority"
                + " on cadenzaflow.act_ru_job (priority_ desc, duedate_)");
  }

  @Test
  void alreadyValidIndexIsLeftAlone() throws SQLException {
    PostgresFixture fixture = new PostgresFixture("cadenzaflow");
    fixture.indexValidityReturns(Boolean.TRUE, Boolean.TRUE);

    plugin.postProcessEngineBuild(fixture.engine);

    verify(fixture.connection, never()).createStatement();
  }

  @Test
  void aFailingConnectionIsAWarningNotABootFailure() throws SQLException {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getDatabaseType()).thenReturn("postgres");
    when(configuration.getDatabaseSchema()).thenReturn("cadenzaflow");
    DataSource dataSource = mock(DataSource.class);
    when(configuration.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenThrow(new SQLException("boom", "08001"));
    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getProcessEngineConfiguration()).thenReturn(configuration);

    assertThatCode(() -> plugin.postProcessEngineBuild(engine)).doesNotThrowAnyException();
  }

  @Test
  void aFailedUnlockIsSwallowedBecauseTheConnectionCloseReleasesIt() throws SQLException {
    PostgresFixture fixture = new PostgresFixture("cadenzaflow");
    fixture.indexValidityReturns(Boolean.TRUE, Boolean.TRUE);
    when(fixture.unlockStatement.execute()).thenThrow(new SQLException("gone", "57P01"));

    assertThatCode(() -> plugin.postProcessEngineBuild(fixture.engine))
        .doesNotThrowAnyException();
  }

  // -------------------------------------------------------------------------
  // Schema resolution
  // -------------------------------------------------------------------------

  @Test
  void schemaComesFromDatabaseSchemaFirstThenThePrefixThenNull() {
    assertThat(JobPriorityIndexPlugin.resolveSchema(configurationWith("cadenzaflow", null)))
        .isEqualTo("cadenzaflow");
    assertThat(JobPriorityIndexPlugin.resolveSchema(configurationWith(null, "cadenzaflow.")))
        .isEqualTo("cadenzaflow");
    assertThat(JobPriorityIndexPlugin.resolveSchema(configurationWith(" ", null))).isNull();
    assertThat(JobPriorityIndexPlugin.resolveSchema(configurationWith(null, null))).isNull();
  }

  @Test
  void aSchemaThatIsNotAPlainIdentifierIsNotInterpolatedIntoDdl() {
    assertThat(
            JobPriorityIndexPlugin.resolveSchema(
                configurationWith("bad.name; drop table act_ru_job", null)))
        .isNull();
  }

  // -------------------------------------------------------------------------
  // The advisory-locked schema-operations wrapper
  // -------------------------------------------------------------------------

  @Test
  void preInitWrapsTheSchemaOperationsCommand() {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    SchemaOperationsCommand original = mock(SchemaOperationsCommand.class);
    when(configuration.getSchemaOperationsCommand()).thenReturn(original);

    plugin.preInit(configuration);

    ArgumentCaptor<SchemaOperationsCommand> captor =
        ArgumentCaptor.forClass(SchemaOperationsCommand.class);
    verify(configuration).setSchemaOperationsCommand(captor.capture());
    assertThat(captor.getValue())
        .isInstanceOfSatisfying(
            JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations.class,
            wrapper -> assertThat(wrapper.delegate()).isSameAs(original));
  }

  @Test
  void wrapperTakesTheTransactionLockOnPostgresThenDelegates() throws SQLException {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    PreparedStatement lockStatement = mock(PreparedStatement.class);
    when(commandContext.getDbSqlSession().getSqlSession().getConnection()
            .prepareStatement(startsWith("select pg_advisory_xact_lock")))
        .thenReturn(lockStatement);

    new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate).execute(commandContext);

    InOrder order = inOrder(lockStatement, delegate);
    order.verify(lockStatement).setLong(1, JobPriorityIndexPlugin.SCHEMA_DDL_LOCK_KEY);
    order.verify(lockStatement).execute();
    order.verify(delegate).execute(commandContext);
  }

  @Test
  void wrapperLogsTheLockWaitAtInfoOnceTheLockIsHeld() throws SQLException {
    ListAppender<ILoggingEvent> captured = captureThePluginLog();
    try {
      CommandContext commandContext = commandContextOn("postgres");
      stubLockStatement(commandContext);

      new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(
              mock(SchemaOperationsCommand.class))
          .execute(commandContext);

      assertThat(captured.list)
          .filteredOn(event -> event.getLevel() == Level.INFO)
          .extracting(ILoggingEvent::getFormattedMessage)
          .singleElement(InstanceOfAssertFactories.STRING)
          .matches("schema DDL advisory lock acquired after \\d+ ms");
    } finally {
      releaseThePluginLog(captured);
    }
  }

  @Test
  void aFailedLockAcquisitionFailsTheBootAndNeverRunsTheSchemaOperations()
      throws SQLException {
    // The old behavior - WARN "proceed unserialized" and continue - re-opened the exact
    // DDL race the lock exists for, and after a transaction-aborting error (deadlock
    // detected, CI run 39504862831) it could only die later as a misleading ENGINE-03018.
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    when(commandContext.getDbSqlSession().getSqlSession().getConnection()
            .prepareStatement(anyString()))
        .thenThrow(new SQLException("deadlock detected", "40P01"));

    assertThatThrownBy(
            () ->
                new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate)
                    .execute(commandContext))
        .isInstanceOf(org.cadenzaflow.bpm.engine.ProcessEngineException.class)
        .hasMessageContaining("refusing to run schema operations unserialized")
        .hasMessageContaining("deadlock detected");
    verify(delegate, never()).execute(commandContext);
  }

  @Test
  void theIndexAndSchemaLocksAreDistinctKeys() {
    // Shared, they deadlock by construction: CREATE INDEX CONCURRENTLY under the key
    // waits for every open transaction, including a peer's schema-ops transaction
    // queued on that same key (CI run 39504862831).
    assertThat(JobPriorityIndexPlugin.INDEX_DDL_LOCK_KEY)
        .isNotEqualTo(JobPriorityIndexPlugin.SCHEMA_DDL_LOCK_KEY);
  }

  @Test
  void wrapperDelegatesDirectlyOffPostgres() {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = mock(CommandContext.class);
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(commandContext.getProcessEngineConfiguration()).thenReturn(configuration);
    when(configuration.getDatabaseType()).thenReturn("h2");

    new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate).execute(commandContext);

    verify(delegate).execute(commandContext);
    verify(commandContext, never()).getDbSqlSession();
  }

  // The former wrapperProceedsUnserializedWhenTheLockCannotBeTaken is gone on purpose:
  // CF-FRESH-RACE-METADATA-FLAKE-1 flipped the contract to fail-closed, pinned by
  // aFailedLockAcquisitionFailsTheBootAndNeverRunsTheSchemaOperations above.

  @Test
  void wrapperFailsBootWhenCurrentSchemaDiffersFromTheStatedSchema() throws SQLException {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    when(commandContext.getProcessEngineConfiguration().getDatabaseSchema())
        .thenReturn("cadenzaflow");
    stubLockStatement(commandContext);
    stubCurrentSchema(commandContext, "public");

    assertThatThrownBy(
            () ->
                new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate)
                    .execute(commandContext))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Engine schema mismatch")
        .hasMessageContaining("'cadenzaflow'")
        .hasMessageContaining("'public'")
        .hasMessageContaining("currentSchema=cadenzaflow");
    verify(delegate, never()).execute(commandContext);
  }

  @Test
  void wrapperAcceptsAMatchingCurrentSchemaAndDelegates() throws SQLException {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    when(commandContext.getProcessEngineConfiguration().getDatabaseSchema())
        .thenReturn("cadenzaflow");
    stubLockStatement(commandContext);
    stubCurrentSchema(commandContext, "cadenzaflow");

    assertThatCode(
            () ->
                new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate)
                    .execute(commandContext))
        .doesNotThrowAnyException();
    verify(delegate).execute(commandContext);
  }

  @Test
  void wrapperSkipsTheSchemaCheckWhenNoSchemaIsStated() throws SQLException {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    when(commandContext.getProcessEngineConfiguration().getDatabaseSchema()).thenReturn(" ");
    stubLockStatement(commandContext);

    new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate).execute(commandContext);

    verify(delegate).execute(commandContext);
    verify(commandContext.getDbSqlSession().getSqlSession().getConnection(), never())
        .prepareStatement("select current_schema()");
  }

  @Test
  void wrapperProceedsWhenCurrentSchemaCannotBeRead() throws SQLException {
    SchemaOperationsCommand delegate = mock(SchemaOperationsCommand.class);
    CommandContext commandContext = commandContextOn("postgres");
    when(commandContext.getProcessEngineConfiguration().getDatabaseSchema())
        .thenReturn("cadenzaflow");
    stubLockStatement(commandContext);
    when(commandContext.getDbSqlSession().getSqlSession().getConnection()
            .prepareStatement("select current_schema()"))
        .thenThrow(new SQLException("boom"));

    assertThatCode(
            () ->
                new JobPriorityIndexPlugin.AdvisoryLockedSchemaOperations(delegate)
                    .execute(commandContext))
        .doesNotThrowAnyException();
    verify(delegate).execute(commandContext);
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static ListAppender<ILoggingEvent> captureThePluginLog() {
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    captured.start();
    ((Logger) LoggerFactory.getLogger(JobPriorityIndexPlugin.class)).addAppender(captured);
    return captured;
  }

  private static void releaseThePluginLog(ListAppender<ILoggingEvent> captured) {
    ((Logger) LoggerFactory.getLogger(JobPriorityIndexPlugin.class)).detachAppender(captured);
    captured.stop();
  }

  private static void stubLockStatement(CommandContext commandContext) throws SQLException {
    when(commandContext.getDbSqlSession().getSqlSession().getConnection()
            .prepareStatement(startsWith("select pg_advisory_xact_lock")))
        .thenReturn(mock(PreparedStatement.class));
  }

  private static void stubCurrentSchema(CommandContext commandContext, String schema)
      throws SQLException {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getString(1)).thenReturn(schema);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(commandContext.getDbSqlSession().getSqlSession().getConnection()
            .prepareStatement("select current_schema()"))
        .thenReturn(statement);
  }

  private static ProcessEngineConfigurationImpl configurationWith(String schema, String prefix) {
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getDatabaseSchema()).thenReturn(schema);
    when(configuration.getDatabaseTablePrefix()).thenReturn(prefix);
    return configuration;
  }

  private static CommandContext commandContextOn(String databaseType) {
    CommandContext commandContext = mock(CommandContext.class);
    ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
    when(commandContext.getProcessEngineConfiguration()).thenReturn(configuration);
    when(configuration.getDatabaseType()).thenReturn(databaseType);
    DbSqlSession dbSqlSession = mock(DbSqlSession.class);
    SqlSession sqlSession = mock(SqlSession.class);
    Connection connection = mock(Connection.class);
    when(commandContext.getDbSqlSession()).thenReturn(dbSqlSession);
    when(dbSqlSession.getSqlSession()).thenReturn(sqlSession);
    when(sqlSession.getConnection()).thenReturn(connection);
    return commandContext;
  }

  /** A mocked Postgres connection wired for the plugin's exact statement sequence. */
  private static final class PostgresFixture {
    final ProcessEngine engine = mock(ProcessEngine.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement lockStatement = mock(PreparedStatement.class);
    final PreparedStatement unlockStatement = mock(PreparedStatement.class);
    final PreparedStatement validityStatement = mock(PreparedStatement.class);
    final Statement ddlStatement = mock(Statement.class);

    PostgresFixture(String schema) throws SQLException {
      ProcessEngineConfigurationImpl configuration = mock(ProcessEngineConfigurationImpl.class);
      when(configuration.getDatabaseType()).thenReturn("postgres");
      when(configuration.getDatabaseSchema()).thenReturn(schema);
      DataSource dataSource = mock(DataSource.class);
      when(configuration.getDataSource()).thenReturn(dataSource);
      when(dataSource.getConnection()).thenReturn(connection);
      when(engine.getProcessEngineConfiguration()).thenReturn(configuration);
      when(connection.prepareStatement(startsWith("select pg_advisory_lock")))
          .thenReturn(lockStatement);
      when(connection.prepareStatement(startsWith("select pg_advisory_unlock")))
          .thenReturn(unlockStatement);
      when(connection.prepareStatement(contains("indisvalid"))).thenReturn(validityStatement);
      when(connection.createStatement()).thenReturn(ddlStatement);
    }

    /**
     * Scripts the two validity reads: before (null = the index does not exist) and the
     * read-back after the DDL.
     */
    void indexValidityReturns(Boolean before, Boolean after) throws SQLException {
      ResultSet first = resultSetOf(before);
      ResultSet second = resultSetOf(after);
      when(validityStatement.executeQuery()).thenReturn(first, second);
    }

    private static ResultSet resultSetOf(Boolean validity) throws SQLException {
      ResultSet resultSet = mock(ResultSet.class);
      when(resultSet.next()).thenReturn(validity != null);
      if (validity != null) {
        when(resultSet.getBoolean(1)).thenReturn(validity);
      }
      return resultSet;
    }
  }
}
