package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import java.util.List;

/**
 * Serializable result of one {@code LlmInvokeActivity} execution: the model's assistant turn.
 *
 * <p>This is a projection of a Spring AI {@code ChatResponse}'s assistant message plus its response
 * metadata (usage/model/id). The activity reconstructs Spring AI types internally; the workflow only
 * ever sees this record, and aggregates the metadata across turns into an {@link AgentResult}.
 *
 * @param text             assistant text content (may be empty when the model only requests tools)
 * @param toolCalls        tool calls the model wants executed; empty marks the terminal turn
 * @param finishReason     provider finish reason, for diagnostics
 * @param promptTokens     prompt/input tokens for this turn, or {@code null} if unreported
 * @param completionTokens completion/output tokens for this turn, or {@code null} if unreported
 * @param totalTokens      total tokens for this turn, or {@code null} if unreported
 * @param model            model id from this turn's response metadata (may differ from requested), or {@code null}
 * @param responseId       provider-assigned response id, or {@code null} if absent
 */
public record LlmResult(
    String text,
    List<ToolCallRecord> toolCalls,
    String finishReason,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    String model,
    String responseId) {

  /** Convenience for turns without captured response metadata (tests, tool-only synthesis). */
  public LlmResult(String text, List<ToolCallRecord> toolCalls, String finishReason) {
    this(text, toolCalls, finishReason, null, null, null, null, null);
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  /** Maps this assistant turn to a conversation record for history reconstruction. */
  public MessageRecord toMessageRecord() {
    return MessageRecord.assistant(text, toolCalls == null ? List.of() : toolCalls);
  }
}
