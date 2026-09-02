package org.opentmf.cadenzaflow.extensions.repository;

import java.util.Optional;
import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.cadenzaflow.bpm.engine.RepositoryService;
import org.cadenzaflow.bpm.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;

/**
 * Resolves a process definition id to its key, name and version. No cache of its own:
 * {@code RepositoryService.getProcessDefinition} answers from the engine's deployment
 * cache, which already holds every definition this service touches.
 *
 * @author Cezmi Aslan
 */
@Component
public class ProcessDefinitionLookup {

  private final RepositoryService repositoryService;

  public ProcessDefinitionLookup(RepositoryService repositoryService) {
    this.repositoryService = repositoryService;
  }

  /** Empty when the definition was deleted between the query and this lookup. */
  public Optional<ProcessDefinition> byId(String processDefinitionId) {
    try {
      return Optional.ofNullable(repositoryService.getProcessDefinition(processDefinitionId));
    } catch (ProcessEngineException _) {
      return Optional.empty();
    }
  }
}
