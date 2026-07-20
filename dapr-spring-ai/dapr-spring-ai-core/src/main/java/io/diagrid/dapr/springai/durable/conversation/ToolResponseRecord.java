package io.diagrid.dapr.springai.durable.conversation;

/**
 * Serializable projection of Spring AI's {@code ToolResponseMessage.ToolResponse} record.
 *
 * @param id           tool-call id this response answers (matches {@link ToolCallRecord#id()})
 * @param name         tool name that produced the response
 * @param responseData tool result, serialized to a string
 */
public record ToolResponseRecord(String id, String name, String responseData) {
}
