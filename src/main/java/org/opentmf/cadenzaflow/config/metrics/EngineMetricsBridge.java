package org.opentmf.cadenzaflow.config.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.cadenzaflow.bpm.engine.ManagementService;
import org.cadenzaflow.bpm.engine.ProcessEngine;
import org.cadenzaflow.bpm.engine.RuntimeService;
import org.cadenzaflow.bpm.engine.management.Metrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bridges the engine's built-in BPM metrics (ACT_RU_METER_LOG) and a few live counts
 * into Micrometer, so they appear on /actuator/prometheus alongside the JVM/HTTP
 * families. Without this bridge the engine metrics are only reachable via
 * {@code engine-rest/metrics}.
 *
 * <p>Each meter resolves lazily at scrape time (one bounded engine query per meter),
 * so an idle scrape interval costs a handful of aggregate queries. Disable with
 * {@code cadenzaflow.metrics.engine-bridge.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(name = "cadenzaflow.metrics.engine-bridge.enabled",
    havingValue = "true", matchIfMissing = true)
public class EngineMetricsBridge {

  private static final String PREFIX = "cadenzaflow.engine.";

  /** Micrometer meter name -> engine metric name (cumulative sums, monotonic). */
  private static final Map<String, String> ENGINE_SUMS = Map.of(
      PREFIX + "root.process.instance.start", Metrics.ROOT_PROCESS_INSTANCE_START,
      PREFIX + "activity.instance.start", Metrics.ACTIVTY_INSTANCE_START,
      PREFIX + "activity.instance.end", Metrics.ACTIVTY_INSTANCE_END,
      PREFIX + "job.successful", Metrics.JOB_SUCCESSFUL,
      PREFIX + "job.failed", Metrics.JOB_FAILED,
      PREFIX + "job.acquisition.attempt", Metrics.JOB_ACQUISITION_ATTEMPT,
      PREFIX + "job.acquired.success", Metrics.JOB_ACQUIRED_SUCCESS,
      PREFIX + "job.acquired.failure", Metrics.JOB_ACQUIRED_FAILURE,
      PREFIX + "job.execution.rejected", Metrics.JOB_EXECUTION_REJECTED);

  public EngineMetricsBridge(ProcessEngine processEngine, MeterRegistry registry) {
    ManagementService managementService = processEngine.getManagementService();
    RuntimeService runtimeService = processEngine.getRuntimeService();

    ENGINE_SUMS.forEach((meterName, engineMetric) ->
        FunctionCounter.builder(meterName, managementService,
                ms -> ms.createMetricsQuery().name(engineMetric).sum())
            .description("Engine metric " + engineMetric + " (cumulative)")
            .register(registry));

    Gauge.builder(PREFIX + "process.instances.active", runtimeService,
            rs -> rs.createProcessInstanceQuery().count())
        .description("Currently running process instances")
        .register(registry);

    Gauge.builder(PREFIX + "incidents.open", runtimeService,
            rs -> rs.createIncidentQuery().count())
        .description("Currently open incidents")
        .register(registry);
  }
}
