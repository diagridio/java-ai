package io.diagrid.dapr.springai.durable.workflow;

/**
 * Input to {@code ToolInvokeActivity}: one tool call the model requested.
 *
 * @param callId    tool-call id, echoed back on the result so it matches the assistant turn
 * @param toolName  tool to invoke (the {@link ToolRegistry} key)
 * @param arguments model-produced JSON arguments string
 * @param trace     observability context (instance id / workflow name / trace carrier)
 */
public record ToolActivityInput(String callId, String toolName, String arguments, ActivityTrace trace) {

  /** Convenience for standalone/test use with no observability context. */
  public ToolActivityInput(String callId, String toolName, String arguments) {
    this(callId, toolName, arguments, ActivityTrace.NONE);
  }
}
