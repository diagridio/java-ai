package io.diagrid.springai.durable.workflow;

/**
 * Serializable output of {@link AgentWorkflow}: the final assistant text plus response metadata
 * aggregated across the chat-and-tools loop. Replaces the bare final-text {@code String} output so
 * upstream advisors and user code reading {@code chatResponse.getMetadata()} /
 * {@code getResult().getMetadata()} see real usage/model/finish-reason values instead of empty
 * defaults.
 *
 * <p>No Spring AI types — the advisor maps this back onto a {@code ChatResponse}. Aggregation is a
 * pure function of the awaited {@link LlmResult}s (see {@link AgentWorkflow#aggregate}), so it is
 * replay-safe by construction. {@link #finalText()} preserves the previous String output.
 *
 * @param finalText       the final assistant message text
 * @param finishReason    finish reason of the final LLM turn, or {@code null}
 * @param aggregatedUsage token usage summed across all LLM turns, or {@code null} if none reported
 * @param model           model id from the final turn's response metadata, or {@code null}
 * @param responseId      provider-assigned response id from the final turn, or {@code null}
 * @param turns           number of LLM turns executed in the loop
 */
public record AgentResult(
    String finalText,
    String finishReason,
    TokenUsage aggregatedUsage,
    String model,
    String responseId,
    int turns) {
}
