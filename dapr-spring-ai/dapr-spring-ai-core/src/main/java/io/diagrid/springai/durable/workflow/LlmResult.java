package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import java.util.List;

/**
 * Serializable result of one {@code LlmInvokeActivity} execution: the model's assistant turn.
 *
 * <p>This is a projection of a Spring AI {@code ChatResponse}'s assistant message. The activity
 * reconstructs Spring AI types internally; the workflow only ever sees this record.
 *
 * @param text         assistant text content (may be empty when the model only requests tools)
 * @param toolCalls    tool calls the model wants executed; empty marks the terminal turn
 * @param finishReason provider finish reason, for diagnostics
 */
public record LlmResult(String text, List<ToolCallRecord> toolCalls, String finishReason) {

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  /** Maps this assistant turn to a conversation record for history reconstruction. */
  public MessageRecord toMessageRecord() {
    return MessageRecord.assistant(text, toolCalls == null ? List.of() : toolCalls);
  }
}
