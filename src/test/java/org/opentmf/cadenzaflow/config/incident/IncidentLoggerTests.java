package org.opentmf.cadenzaflow.config.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.cadenzaflow.bpm.engine.impl.context.Context;
import org.cadenzaflow.bpm.engine.impl.incident.IncidentContext;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cadenzaflow.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for the incident-logging decision (FR-06).
 *
 * <p>The handler delegates to {@code DefaultIncidentHandler} for the engine-side persistence
 * of the incident, which needs a real command context. That is engine behaviour and is
 * deliberately out of scope here: these tests assert only WHAT GETS LOGGED, which is the part
 * this repository owns. Logging happens before the delegation, so whatever the superclass makes
 * of the mocked context does not affect the assertions.
 *
 * @author cezmi-aslan
 */
class IncidentLoggerTests {

  private static final String EXECUTION_ID = "exec-1";

  private ListAppender<ILoggingEvent> captured;
  private Logger logger;

  @BeforeEach
  void captureTheHandlersLog() {
    logger = (Logger) LoggerFactory.getLogger(IncidentLogger.class);
    captured = new ListAppender<>();
    captured.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
    captured.stop();
  }

  /** Builds a command context whose managers answer with the given deployment list. */
  private CommandContext commandContextWith(
      ExecutionEntity execution, List<DeploymentEntity> deployments) {
    CommandContext commandContext = mock(CommandContext.class, RETURNS_DEEP_STUBS);
    when(commandContext.getExecutionManager().findExecutionById(EXECUTION_ID))
        .thenReturn(execution);
    when(commandContext.getDeploymentManager().findDeploymentsByIds(any(String[].class)))
        .thenReturn(deployments);
    return commandContext;
  }

  private ExecutionEntity executionWithProcessDefinitionName(String processDefinitionName) {
    ExecutionEntity execution = mock(ExecutionEntity.class, RETURNS_DEEP_STUBS);
    when(execution.getProcessDefinition().getDeploymentId()).thenReturn("dep-1");
    when(execution.getProcessDefinition().getName()).thenReturn(processDefinitionName);
    when(execution.getProcessDefinition().getVersion()).thenReturn(7);
    when(execution.getProcessInstanceId()).thenReturn("pi-1");
    ActivityImpl activity = mock(ActivityImpl.class);
    when(activity.getName()).thenReturn("Charge the card");
    when(execution.getActivity()).thenReturn(activity);
    return execution;
  }

  private static IncidentContext incidentOn(String executionId, String activityId) {
    IncidentContext context = new IncidentContext();
    context.setExecutionId(executionId);
    context.setActivityId(activityId);
    return context;
  }

  private void handle(IncidentContext context) {
    catchThrowable(
        () ->
            new IncidentLogger(Incident.FAILED_JOB_HANDLER_TYPE)
                .handleIncident(context, "retries exhausted"));
  }

  @Test
  void logAWarningNamingTheDeploymentProcessAndActivity() {
    ExecutionEntity execution = executionWithProcessDefinitionName("Order fulfilment");
    DeploymentEntity deployment = new DeploymentEntity();
    deployment.setName("orders.bar");
    // Built before the static stubbing below: creating mocks inside a `when(...)` argument
    // nests one stubbing in another, which Mockito rejects as UnfinishedStubbing.
    CommandContext commandContext = commandContextWith(execution, List.of(deployment));

    try (MockedStatic<Context> engineContext = mockStatic(Context.class)) {
      engineContext.when(Context::getCommandContext).thenReturn(commandContext);
      handle(incidentOn(EXECUTION_ID, "ServiceTask_1"));
    }

    assertThat(captured.list).hasSize(1);
    ILoggingEvent event = captured.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("orders.bar")
        .contains("Order fulfilment")
        .contains("version 7")
        .contains("Charge the card")
        .contains("pi-1")
        .contains("retries exhausted");
  }

  @Test
  void fallBackToTheActivityIdWhenTheProcessDefinitionHasNoName() {
    ExecutionEntity execution = executionWithProcessDefinitionName(null);
    CommandContext commandContext = commandContextWith(execution, List.of());

    try (MockedStatic<Context> engineContext = mockStatic(Context.class)) {
      engineContext.when(Context::getCommandContext).thenReturn(commandContext);
      handle(incidentOn(EXECUTION_ID, "ServiceTask_1"));
    }

    assertThat(captured.list).hasSize(1);
    // No deployment was found, so the deployment name stays null rather than throwing.
    assertThat(captured.list.get(0).getFormattedMessage())
        .contains("'null'")
        .contains("ServiceTask_1");
  }

  @Test
  void stayQuietForAnIncidentThatHasNoExecution() {
    // Raised e.g. during a process instance version migration: not actionable, so logging it
    // would only add noise.
    CommandContext commandContext = mock(CommandContext.class);

    try (MockedStatic<Context> engineContext = mockStatic(Context.class)) {
      engineContext.when(Context::getCommandContext).thenReturn(commandContext);
      handle(incidentOn(null, "ServiceTask_1"));
    }

    assertThat(captured.list).isEmpty();
  }

  @Test
  void reportRatherThanPropagateAFailureWhileBuildingTheLogLine() {
    // The handler is observational: a failure assembling its own log line must never break the
    // engine's incident handling.
    CommandContext commandContext = mock(CommandContext.class, RETURNS_DEEP_STUBS);
    when(commandContext.getExecutionManager().findExecutionById(EXECUTION_ID))
        .thenThrow(new IllegalStateException("no session"));

    try (MockedStatic<Context> engineContext = mockStatic(Context.class)) {
      engineContext.when(Context::getCommandContext).thenReturn(commandContext);
      handle(incidentOn(EXECUTION_ID, "ServiceTask_1"));
    }

    assertThat(captured.list).hasSize(1);
    assertThat(captured.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    assertThat(captured.list.get(0).getFormattedMessage())
        .contains("Exception while logging cadenzaflow incident");
  }
}
