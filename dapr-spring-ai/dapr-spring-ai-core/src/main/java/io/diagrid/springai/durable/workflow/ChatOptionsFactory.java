package io.diagrid.springai.durable.workflow;

import java.util.List;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the per-call {@code ChatOptions} for the LLM activity.
 *
 * <p>This is the provider-options seam. Spring AI ChatModels require their own {@code ChatOptions}
 * subtype as runtime options (e.g. {@code OllamaChatModel.call} casts to {@code OllamaChatOptions}),
 * so the concrete factory is supplied by whoever wires the activity to a specific ChatModel; the
 * framework-agnostic core only depends on the {@link ChatOptions} interface.
 */
@FunctionalInterface
public interface ChatOptionsFactory {

  /**
   * @param spec          portable chat options captured from the original call (model, temperature,
   *                      max tokens, top-p, etc.); non-null fields override the provider defaults
   * @param toolCallbacks tool callbacks to advertise to the model (definition-only; never executed)
   * @return provider-appropriate chat options
   */
  ChatOptions create(ChatOptionsSpec spec, List<ToolCallback> toolCallbacks);
}
