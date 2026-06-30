package io.diagrid.springai.durable.conversation;

/**
 * Serializable projection of Spring AI's {@code AssistantMessage.ToolCall} record.
 *
 * @param id        provider-assigned tool-call id, echoed back on the matching tool response
 * @param type      tool-call type (typically {@code "function"})
 * @param name      tool name the model wants to invoke
 * @param arguments JSON-encoded arguments string produced by the model
 */
public record ToolCallRecord(String id, String type, String name, String arguments) {
}
