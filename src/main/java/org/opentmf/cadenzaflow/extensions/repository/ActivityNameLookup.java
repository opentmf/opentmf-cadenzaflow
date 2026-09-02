package org.opentmf.cadenzaflow.extensions.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.cadenzaflow.bpm.engine.ProcessEngineException;
import org.cadenzaflow.bpm.engine.RepositoryService;
import org.cadenzaflow.bpm.model.bpmn.BpmnModelInstance;
import org.cadenzaflow.bpm.model.bpmn.instance.FlowNode;
import org.springframework.stereotype.Component;

/**
 * Resolves a BPMN element id to its human-readable name and element type, per process
 * definition. The model is parsed once per definition id and cached forever — process
 * definitions are immutable, so there is nothing to evict.
 *
 * @author Cezmi Aslan
 */
@Component
public class ActivityNameLookup {

  /** What the report shows for one BPMN element: its name attribute and element type. */
  public record ActivityInfo(String name, String type) {}

  private final RepositoryService repositoryService;
  private final ConcurrentHashMap<String, Map<String, ActivityInfo>> byDefinitionId =
      new ConcurrentHashMap<>();

  public ActivityNameLookup(RepositoryService repositoryService) {
    this.repositoryService = repositoryService;
  }

  /**
   * Empty when the definition no longer exists or the activity id is not in the model
   * (e.g. removed in a later version) — a missing name is a display gap, never an error.
   */
  public Optional<ActivityInfo> activity(String processDefinitionId, String activityId) {
    return Optional.ofNullable(
        byDefinitionId.computeIfAbsent(processDefinitionId, this::load).get(activityId));
  }

  private Map<String, ActivityInfo> load(String processDefinitionId) {
    try {
      BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinitionId);
      Map<String, ActivityInfo> activities = new HashMap<>();
      for (FlowNode node : model.getModelElementsByType(FlowNode.class)) {
        activities.put(
            node.getId(),
            new ActivityInfo(node.getName(), node.getElementType().getTypeName()));
      }
      return Map.copyOf(activities);
    } catch (ProcessEngineException _) {
      // Deleted between the select and this lookup - answer with no names.
      return Map.of();
    }
  }
}
