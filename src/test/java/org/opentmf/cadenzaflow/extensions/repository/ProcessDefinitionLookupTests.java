package org.opentmf.cadenzaflow.extensions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.cadenzaflow.bpm.engine.RepositoryService;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.Test;

/**
 * @author Cezmi Aslan
 */
class ProcessDefinitionLookupTests {

  private final RepositoryService repositoryService = mock(RepositoryService.class);
  private final ProcessDefinitionLookup lookup = new ProcessDefinitionLookup(repositoryService);

  @Test
  void answersTheDefinitionWhenTheEngineKnowsIt() {
    ProcessDefinition definition = mock(ProcessDefinition.class);
    when(repositoryService.getProcessDefinition("def-1")).thenReturn(definition);
    assertThat(lookup.byId("def-1")).contains(definition);
  }

  @Test
  void answersEmptyWhenTheDefinitionIsGone() {
    when(repositoryService.getProcessDefinition("def-1"))
        .thenThrow(new ProcessEngineException("no deployed process definition"));
    assertThat(lookup.byId("def-1")).isEmpty();
  }
}
