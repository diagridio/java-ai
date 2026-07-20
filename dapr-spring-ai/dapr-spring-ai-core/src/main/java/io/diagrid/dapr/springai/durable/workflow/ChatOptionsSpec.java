package io.diagrid.dapr.springai.durable.workflow;

import java.util.List;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Serializable snapshot of the <b>portable</b> Spring AI {@link ChatOptions} surface for one call.
 *
 * <p>The durable workflow can't carry a provider-specific {@code ChatOptions} object across the wire
 * (those hold non-serializable bits like tool callbacks, and don't round-trip polymorphically), so
 * the advisor captures the portable, provider-agnostic option values here and the activity re-applies
 * them onto the provider's own default options. Provider-<i>specific</i> options set per call (e.g.
 * an Ollama {@code numCtx} on the prompt) are NOT carried — but provider options configured as the
 * model's defaults are preserved, since the activity starts from {@code ChatModel.getDefaultOptions()}.
 *
 * <p>All fields are nullable; a {@code null} field means "leave the provider default untouched".
 *
 * @param model            model identifier
 * @param temperature      sampling temperature
 * @param frequencyPenalty frequency penalty
 * @param maxTokens        max output tokens
 * @param presencePenalty  presence penalty
 * @param stopSequences    stop sequences
 * @param topK             top-k sampling
 * @param topP             top-p (nucleus) sampling
 */
public record ChatOptionsSpec(
    String model,
    Double temperature,
    Double frequencyPenalty,
    Integer maxTokens,
    Double presencePenalty,
    List<String> stopSequences,
    Integer topK,
    Double topP) {

  /** An empty spec (every option left at the provider default). */
  public static ChatOptionsSpec empty() {
    return new ChatOptionsSpec(null, null, null, null, null, null, null, null);
  }

  /** Captures the portable option values from a Spring AI {@link ChatOptions} (or empty if null). */
  public static ChatOptionsSpec from(ChatOptions options) {
    if (options == null) {
      return empty();
    }
    return new ChatOptionsSpec(
        options.getModel(),
        options.getTemperature(),
        options.getFrequencyPenalty(),
        options.getMaxTokens(),
        options.getPresencePenalty(),
        options.getStopSequences(),
        options.getTopK(),
        options.getTopP());
  }

  /** Applies every non-null option onto a provider's options builder, leaving the rest as defaults. */
  public void applyTo(ChatOptions.Builder<?> builder) {
    if (model != null) {
      builder.model(model);
    }
    if (temperature != null) {
      builder.temperature(temperature);
    }
    if (frequencyPenalty != null) {
      builder.frequencyPenalty(frequencyPenalty);
    }
    if (maxTokens != null) {
      builder.maxTokens(maxTokens);
    }
    if (presencePenalty != null) {
      builder.presencePenalty(presencePenalty);
    }
    if (stopSequences != null) {
      builder.stopSequences(stopSequences);
    }
    if (topK != null) {
      builder.topK(topK);
    }
    if (topP != null) {
      builder.topP(topP);
    }
  }
}
