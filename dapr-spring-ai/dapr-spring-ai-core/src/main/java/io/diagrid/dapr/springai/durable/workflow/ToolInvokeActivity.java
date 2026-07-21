package io.diagrid.dapr.springai.durable.workflow;

import io.diagrid.dapr.springai.durable.tracing.DurableTracing;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import io.dapr.workflows.WorkflowActivity;
import io.dapr.workflows.WorkflowActivityContext;

/**
 * Activity that executes one tool call by dispatching to the {@link ToolRegistry} by name.
 *
 * <p>Each tool call from a model turn runs as its own activity so recovery is granular: the runtime
 * records every completed activity and never re-runs it on replay. The core adds no idempotency
 * layer of its own, and a tool receives only its arguments — so a tool whose side effects must be
 * dedup-safe across a retry or a client reissue should key off a business value in those arguments
 * (e.g. a booking reference).
 *
 * <p>The body runs inside a {@link DurableTracing} activity scope (restoring the originating trace
 * context), and the instance id + tool name are placed in SLF4J MDC for log correlation regardless
 * of any tracing backend.
 */
public final class ToolInvokeActivity implements WorkflowActivity {

  private final ToolRegistry registry;
  private final DurableTracing tracing;

  /** Uses {@link DurableTracing#NOOP} — no observability. */
  public ToolInvokeActivity(ToolRegistry registry) {
    this(registry, DurableTracing.NOOP);
  }

  public ToolInvokeActivity(ToolRegistry registry, DurableTracing tracing) {
    this.registry = registry;
    this.tracing = tracing;
  }

  @Override
  public Object run(WorkflowActivityContext ctx) {
    ToolActivityInput input = ctx.getInput(ToolActivityInput.class);
    ActivityTrace trace = input.trace();

    String previousInstanceId = MDC.get(DurableTracing.KEY_INSTANCE_ID);
    String previousToolName = MDC.get(DurableTracing.KEY_TOOL_NAME);
    if (trace.instanceId() != null) {
      MDC.put(DurableTracing.KEY_INSTANCE_ID, trace.instanceId());
    }
    MDC.put(DurableTracing.KEY_TOOL_NAME, input.toolName());
    try {
      return tracing.runInActivityScope(
          DurableTracing.TOOL_SPAN, trace.traceContext(), attributes(trace, input.toolName()),
          () -> invoke(input));
    } finally {
      restore(DurableTracing.KEY_INSTANCE_ID, previousInstanceId);
      restore(DurableTracing.KEY_TOOL_NAME, previousToolName);
    }
  }

  private ToolResult invoke(ToolActivityInput input) {
    String result = registry.invoke(input.toolName(), input.arguments());
    return new ToolResult(input.callId(), input.toolName(), result);
  }

  // Span attributes: instance id + workflow name (skipping absent) + the tool name.
  private static Map<String, String> attributes(ActivityTrace trace, String toolName) {
    Map<String, String> attrs = new LinkedHashMap<>();
    if (trace.instanceId() != null) {
      attrs.put(DurableTracing.KEY_INSTANCE_ID, trace.instanceId());
    }
    if (trace.workflowName() != null) {
      attrs.put(DurableTracing.KEY_WORKFLOW_NAME, trace.workflowName());
    }
    attrs.put(DurableTracing.KEY_TOOL_NAME, toolName);
    return attrs;
  }

  // Restore a prior MDC value, removing the key when there was none.
  private static void restore(String key, String previous) {
    if (previous != null) {
      MDC.put(key, previous);
    } else {
      MDC.remove(key);
    }
  }
}
