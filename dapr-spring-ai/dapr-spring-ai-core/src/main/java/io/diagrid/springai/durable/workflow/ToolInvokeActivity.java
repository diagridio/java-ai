package io.diagrid.springai.durable.workflow;

import io.dapr.workflows.WorkflowActivity;
import io.dapr.workflows.WorkflowActivityContext;

/**
 * Activity that executes one tool call by dispatching to the {@link ToolRegistry} by name.
 *
 * <p>Each tool call from a model turn runs as its own activity so recovery is granular: the runtime
 * records every completed activity and never re-runs it on replay. The runtime's
 * {@code taskExecutionId} (available via {@link WorkflowActivityContext#getTaskExecutionId()}) is the
 * hook a tool author can use to dedupe side effects across the narrow at-least-once window; the core
 * does not add its own idempotency layer.
 */
public final class ToolInvokeActivity implements WorkflowActivity {

  private final ToolRegistry registry;

  public ToolInvokeActivity(ToolRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Object run(WorkflowActivityContext ctx) {
    ToolActivityInput input = ctx.getInput(ToolActivityInput.class);
    String result = registry.invoke(input.toolName(), input.arguments());
    return new ToolResult(input.callId(), input.toolName(), result);
  }
}
