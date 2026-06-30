package io.diagrid.springai.durable.workflow;

/**
 * Input to {@code ToolInvokeActivity}: one tool call the model requested.
 *
 * @param callId    tool-call id, echoed back on the result so it matches the assistant turn
 * @param toolName  tool to invoke (the {@link ToolRegistry} key)
 * @param arguments model-produced JSON arguments string
 */
public record ToolActivityInput(String callId, String toolName, String arguments) {
}
