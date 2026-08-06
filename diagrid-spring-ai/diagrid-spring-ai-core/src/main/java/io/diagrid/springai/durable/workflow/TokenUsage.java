package io.diagrid.springai.durable.workflow;

/**
 * Serializable token counts, summed across a workflow's LLM turns. Any field may be {@code null} when
 * the provider reports no usage. Kept free of Spring AI types so it round-trips through the
 * durabletask payload converter; the advisor maps it back to a Spring AI {@code Usage}.
 *
 * @param promptTokens     input/prompt tokens, or {@code null}
 * @param completionTokens output/completion tokens, or {@code null}
 * @param totalTokens      total tokens, or {@code null}
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
