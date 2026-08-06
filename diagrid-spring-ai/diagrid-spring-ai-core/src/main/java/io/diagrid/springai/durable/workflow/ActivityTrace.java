package io.diagrid.springai.durable.workflow;

import java.util.Map;

/**
 * Observability data threaded from the orchestrator into every activity input, so an activity can
 * label its span/logs with the run it belongs to and restore the originating trace context.
 *
 * <p>All three fields are derived deterministically in the orchestrator ({@code ctx.getInstanceId()},
 * {@code ctx.getName()}, and the caller-captured {@code traceContext} from the workflow input), so
 * they are constant across replays — the orchestrator never creates spans, it only forwards data.
 * The pinned {@code WorkflowActivityContext} (dapr-sdk-workflows 1.18.0) exposes no instance id, which
 * is why the id is carried here rather than read activity-side.
 *
 * @param instanceId   the workflow instance id, or {@code null} if unavailable
 * @param workflowName the workflow name, or {@code null} if unavailable
 * @param traceContext W3C propagation carrier captured on the caller thread (never {@code null};
 *                     empty means no propagation)
 */
public record ActivityTrace(String instanceId, String workflowName, Map<String, String> traceContext) {

  /** No observability context (no instance id, no propagation) — the default for standalone/test use. */
  public static final ActivityTrace NONE = new ActivityTrace(null, null, Map.of());

  public ActivityTrace {
    traceContext = traceContext == null ? Map.of() : Map.copyOf(traceContext);
  }
}
