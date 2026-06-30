package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.workflow.ChatOptionsFactory;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Provider-agnostic {@link ChatOptionsFactory}: starts from the user's ChatModel default options
 * (which are the provider's own {@code ChatOptions} subtype — required because e.g. OllamaChatModel
 * casts its runtime options) and layers the durable tool callbacks plus any per-call model and
 * temperature overrides on top.
 */
public final class DefaultChatOptionsFactory implements ChatOptionsFactory {

  private final ChatModel chatModel;

  public DefaultChatOptionsFactory(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @Override
  public ChatOptions create(String model, Double temperature, List<ToolCallback> toolCallbacks) {
    ChatOptions.Builder<?> builder = chatModel.getDefaultOptions().mutate();
    if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder) {
      toolBuilder.toolCallbacks(toolCallbacks);
    }
    if (model != null) {
      builder.model(model);
    }
    if (temperature != null) {
      builder.temperature(temperature);
    }
    return builder.build();
  }
}
