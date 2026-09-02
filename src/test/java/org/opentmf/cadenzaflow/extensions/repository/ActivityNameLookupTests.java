package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.cadenzaflow.bpm.engine.RepositoryService;
import org.cadenzaflow.bpm.model.bpmn.Bpmn;
import org.cadenzaflow.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Cezmi Aslan
 */
class ActivityNameLookupTests {

  private static final String DEFINITION_ID = "childFlow:1:abc";

  private RepositoryService repositoryService;
  private ActivityNameLookup lookup;

  @BeforeEach
  void setUp() {
    repositoryService = mock(RepositoryService.class);
    lookup = new ActivityNameLookup(repositoryService);
  }

  private void modelWithNamedServiceTask() {
    BpmnModelInstance model = Bpmn.createExecutableProcess("childFlow")
        .startEvent("start")
        .serviceTask("callWms").name("Call WMS")
        .endEvent("end")
        .done();
    when(repositoryService.getBpmnModelInstance(DEFINITION_ID)).thenReturn(model);
  }

  @Test
  void resolveNameAndElementTypeByActivityId() {
    modelWithNamedServiceTask();
    assertThat(lookup.activity(DEFINITION_ID, "callWms"))
        .contains(new ActivityNameLookup.ActivityInfo("Call WMS", "serviceTask"));
  }

  @Test
  void answerEmptyForAnActivityIdNotInTheModel() {
    modelWithNamedServiceTask();
    assertThat(lookup.activity(DEFINITION_ID, "removedInThisVersion")).isEmpty();
  }

  @Test
  void answerEmptyWhenTheDefinitionIsGone() {
    when(repositoryService.getBpmnModelInstance(DEFINITION_ID))
        .thenThrow(new ProcessEngineException("no deployed process definition"));
    assertThat(lookup.activity(DEFINITION_ID, "callWms")).isEmpty();
  }

  @Test
  void parseEachDefinitionOnlyOnce() {
    modelWithNamedServiceTask();
    lookup.activity(DEFINITION_ID, "callWms");
    lookup.activity(DEFINITION_ID, "start");
    verify(repositoryService, times(1)).getBpmnModelInstance(DEFINITION_ID);
  }
}
