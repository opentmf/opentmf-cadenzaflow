package org.opentmf.cadenzaflow.config.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.cadenzaflow.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cadenzaflow.bpm.engine.impl.context.Context;
import org.cadenzaflow.bpm.engine.impl.incident.IncidentContext;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cadenzaflow.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cadenzaflow.bpm.engine.runtime.Incident;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

class IncidentLoggerTest {

  private final IncidentLogger handler = new IncidentLogger("failedJob");
  private ListAppender<ILoggingEvent> logAppender;
  private CommandContext commandContext;

  @BeforeEach
  void attachLogAppender() {
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(IncidentLogger.class)).addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    ((Logger) LoggerFactory.getLogger(IncidentLogger.class)).detachAppender(logAppender);
  }

  @Test
  void skipsLoggingForIncidentsWithoutExecution() {
    try (MockedStatic<Context> context = mockStatic(Context.class)) {
      stubEngineContext(context);
      IncidentContext incidentContext = mock(IncidentContext.class);

      Incident incident = handler.handleIncident(incidentContext, "boom");

      assertNotNull(incident);
      assertEquals("failedJob", incident.getIncidentType());
      assertTrue(logAppender.list.isEmpty());
    }
  }

  @Test
  void logsDeploymentAndProcessDefinitionDetails() {
    try (MockedStatic<Context> context = mockStatic(Context.class)) {
      stubEngineContext(context);
      IncidentContext incidentContext = mock(IncidentContext.class);
      when(incidentContext.getExecutionId()).thenReturn("exec-1");
      ExecutionEntity execution = mockExecution("My Process");
      when(commandContext.getExecutionManager().findExecutionById("exec-1")).thenReturn(execution);
      DeploymentEntity deployment = mock(DeploymentEntity.class);
      when(deployment.getName()).thenReturn("deployment-a");
      when(commandContext.getDeploymentManager().findDeploymentsByIds("dep-1"))
          .thenReturn(List.of(deployment));

      Incident incident = handler.handleIncident(incidentContext, "boom");

      assertNotNull(incident);
      assertEquals(1, logAppender.list.size());
      String logLine = logAppender.list.get(0).getFormattedMessage();
      assertTrue(logLine.contains("'deployment-a'"));
      assertTrue(logLine.contains("'My Process (version 3)'"));
      assertTrue(logLine.contains("'boom'"));
    }
  }

  @Test
  void fallsBackToActivityIdWhenProcessDefinitionNameAndDeploymentsMissing() {
    try (MockedStatic<Context> context = mockStatic(Context.class)) {
      stubEngineContext(context);
      IncidentContext incidentContext = mock(IncidentContext.class);
      when(incidentContext.getExecutionId()).thenReturn("exec-1");
      when(incidentContext.getActivityId()).thenReturn("activity-1");
      ExecutionEntity execution = mockExecution(null);
      when(commandContext.getExecutionManager().findExecutionById("exec-1")).thenReturn(execution);
      when(commandContext.getDeploymentManager().findDeploymentsByIds("dep-1"))
          .thenReturn(List.of());

      handler.handleIncident(incidentContext, "boom");

      assertEquals(1, logAppender.list.size());
      assertTrue(logAppender.list.get(0).getFormattedMessage().contains("'activity-1 (version 3)'"));
    }
  }

  @Test
  void neverPropagatesLoggingFailuresToIncidentHandling() {
    try (MockedStatic<Context> context = mockStatic(Context.class)) {
      stubEngineContext(context);
      IncidentContext incidentContext = mock(IncidentContext.class);
      when(incidentContext.getExecutionId()).thenReturn("exec-1");
      ExecutionEntity execution = mockExecution("My Process");
      when(commandContext.getExecutionManager().findExecutionById("exec-1"))
          .thenThrow(new IllegalStateException("db gone"))
          .thenReturn(execution);

      Incident incident = handler.handleIncident(incidentContext, "boom");

      assertNotNull(incident);
      assertEquals(1, logAppender.list.size());
      assertEquals(Level.ERROR, logAppender.list.get(0).getLevel());
    }
  }

  private void stubEngineContext(MockedStatic<Context> context) {
    commandContext = mock(CommandContext.class, RETURNS_DEEP_STUBS);
    context.when(Context::getCommandContext).thenReturn(commandContext);
    context
        .when(Context::getProcessEngineConfiguration)
        .thenReturn(mock(ProcessEngineConfigurationImpl.class, RETURNS_DEEP_STUBS));
  }

  private ExecutionEntity mockExecution(String processDefinitionName) {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    ProcessDefinitionEntity processDefinition = mock(ProcessDefinitionEntity.class);
    when(processDefinition.getName()).thenReturn(processDefinitionName);
    when(processDefinition.getVersion()).thenReturn(3);
    when(processDefinition.getDeploymentId()).thenReturn("dep-1");
    when(execution.getProcessDefinition()).thenReturn(processDefinition);
    ActivityImpl activity = mock(ActivityImpl.class);
    when(activity.getName()).thenReturn("Activity Name");
    when(execution.getActivity()).thenReturn(activity);
    when(execution.getId()).thenReturn("exec-1");
    when(execution.getProcessInstanceId()).thenReturn("pi-1");
    when(execution.getProcessInstance()).thenReturn(mock(ExecutionEntity.class));
    return execution;
  }
}
