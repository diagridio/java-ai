package io.diagrid.springai.durable.testworker;

import io.diagrid.springai.durable.workflow.ChatOptionsFactory;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import java.util.List;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Ollama implementation of the provider-options seam. {@code OllamaChatModel.call} requires its own
 * {@code OllamaChatOptions} as runtime options, so this builds that subtype with the (definition-only)
 * tool callbacks and the portable options captured from the original call applied on top.
 */
public final class OllamaChatOptionsFactory implements ChatOptionsFactory {

  @Override
  public ChatOptions create(ChatOptionsSpec spec, List<ToolCallback> toolCallbacks) {
    OllamaChatOptions.Builder builder = OllamaChatOptions.builder().toolCallbacks(toolCallbacks);
    spec.applyTo(builder);
    return builder.build();
  }
}
