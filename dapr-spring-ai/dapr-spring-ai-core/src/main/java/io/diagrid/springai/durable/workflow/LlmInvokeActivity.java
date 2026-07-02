package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.ConversationDecoder;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import io.dapr.workflows.WorkflowActivity;
import io.dapr.workflows.WorkflowActivityContext;

/**
 * Activity that performs one model turn: decode the conversation, call the {@link ChatModel} with
 * the tool surface advertised, and project the assistant response (text + any tool calls) to a
 * serializable {@link LlmResult}.
 *
 * <p>On the durable path the ChatModel must return tool-call requests rather than executing them, so
 * the orchestrator can dispatch each as its own {@link ToolInvokeActivity}. Tool callbacks are
 * attached as {@link DefinitionOnlyToolCallback}, which carries the tool definition but throws if
 * invoked — the loud backstop, since Spring AI 2.0 GA no longer exposes an options flag to disable
 * in-model execution. All Spring AI types are confined to this activity; the workflow only sees
 * records.
 */
public final class LlmInvokeActivity implements WorkflowActivity {

  private final ChatModel chatModel;
  private final ChatOptionsFactory optionsFactory;
  private final ConversationDecoder decoder = new ConversationDecoder(new MessageCodec());

  public LlmInvokeActivity(ChatModel chatModel, ChatOptionsFactory optionsFactory) {
    this.chatModel = chatModel;
    this.optionsFactory = optionsFactory;
  }

  @Override
  public Object run(WorkflowActivityContext ctx) {
    LlmActivityInput input = ctx.getInput(LlmActivityInput.class);

    List<Message> messages = decoder.decode(input.conversation());

    List<ToolCallback> toolCallbacks =
        input.toolSpecs().stream().map(spec -> (ToolCallback) new DefinitionOnlyToolCallback(spec)).toList();

    ChatOptions options = optionsFactory.create(input.options(), toolCallbacks);
    Prompt prompt = new Prompt(messages, options);

    ChatResponse response = chatModel.call(prompt);
    AssistantMessage assistant = response.getResult().getOutput();

    List<ToolCallRecord> toolCalls =
        assistant.getToolCalls().stream()
            .map(tc -> new ToolCallRecord(tc.id(), tc.type(), tc.name(), tc.arguments()))
            .toList();

    String finishReason = response.getResult().getMetadata().getFinishReason();
    return new LlmResult(assistant.getText(), toolCalls, finishReason);
  }
}
