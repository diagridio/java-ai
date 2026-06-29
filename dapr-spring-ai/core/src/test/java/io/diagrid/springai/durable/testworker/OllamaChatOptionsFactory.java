package io.diagrid.springai.durable.testworker;

import io.diagrid.springai.durable.workflow.ChatOptionsFactory;
import java.util.List;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Ollama implementation of the provider-options seam. {@code OllamaChatModel.call} requires its own
 * {@code OllamaChatOptions} as runtime options, so this builds that subtype with the model, optional
 * temperature, and the (definition-only) tool callbacks.
 */
public final class OllamaChatOptionsFactory implements ChatOptionsFactory {

  @Override
  public ChatOptions create(String model, Double temperature, List<ToolCallback> toolCallbacks) {
    OllamaChatOptions.Builder builder = OllamaChatOptions.builder().model(model).toolCallbacks(toolCallbacks);
    if (temperature != null) {
      builder.temperature(temperature);
    }
    return builder.build();
  }
}
