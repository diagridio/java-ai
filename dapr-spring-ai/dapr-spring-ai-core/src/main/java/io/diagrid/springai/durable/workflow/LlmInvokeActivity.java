package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.ConversationDecoder;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import io.diagrid.springai.durable.tracing.DurableTracing;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
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
 *
 * <p>The whole body runs inside a {@link DurableTracing} activity scope (restoring the originating
 * request's trace context) so Spring AI's own ChatModel (gen_ai) observation parents correctly, and
 * the instance id is placed in SLF4J MDC for log correlation regardless of any tracing backend.
 */
public final class LlmInvokeActivity implements WorkflowActivity {

  private final ChatModel chatModel;
  private final ChatOptionsFactory optionsFactory;
  private final DurableTracing tracing;
  private final ConversationDecoder decoder = new ConversationDecoder(new MessageCodec());

  /** Uses {@link DurableTracing#NOOP} — no observability. */
  public LlmInvokeActivity(ChatModel chatModel, ChatOptionsFactory optionsFactory) {
    this(chatModel, optionsFactory, DurableTracing.NOOP);
  }

  public LlmInvokeActivity(ChatModel chatModel, ChatOptionsFactory optionsFactory, DurableTracing tracing) {
    this.chatModel = chatModel;
    this.optionsFactory = optionsFactory;
    this.tracing = tracing;
  }

  @Override
  public Object run(WorkflowActivityContext ctx) {
    LlmActivityInput input = ctx.getInput(LlmActivityInput.class);
    ActivityTrace trace = input.trace();

    String previousInstanceId = MDC.get(DurableTracing.KEY_INSTANCE_ID);
    if (trace.instanceId() != null) {
      MDC.put(DurableTracing.KEY_INSTANCE_ID, trace.instanceId());
    }
    try {
      return tracing.runInActivityScope(
          DurableTracing.LLM_SPAN, trace.traceContext(), attributes(trace), () -> invoke(input));
    } finally {
      restore(DurableTracing.KEY_INSTANCE_ID, previousInstanceId);
    }
  }

  private LlmResult invoke(LlmActivityInput input) {
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

    // Capture response metadata (usage/model/id) so the workflow can aggregate it into AgentResult.
    ChatResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    Integer promptTokens = usage == null ? null : usage.getPromptTokens();
    Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
    Integer totalTokens = usage == null ? null : usage.getTotalTokens();
    String model = metadata == null ? null : blankToNull(metadata.getModel());
    String responseId = metadata == null ? null : blankToNull(metadata.getId());

    return new LlmResult(
        assistant.getText(), toolCalls, finishReason,
        promptTokens, completionTokens, totalTokens, model, responseId);
  }

  // Span attributes: instance id + workflow name, skipping absent values.
  private static Map<String, String> attributes(ActivityTrace trace) {
    Map<String, String> attrs = new java.util.LinkedHashMap<>();
    if (trace.instanceId() != null) {
      attrs.put(DurableTracing.KEY_INSTANCE_ID, trace.instanceId());
    }
    if (trace.workflowName() != null) {
      attrs.put(DurableTracing.KEY_WORKFLOW_NAME, trace.workflowName());
    }
    return attrs;
  }

  // Restore a prior MDC value, removing the key when there was none.
  private static void restore(String key, String previous) {
    if (previous != null) {
      MDC.put(key, previous);
    } else {
      MDC.remove(key);
    }
  }

  // ChatResponseMetadata returns "" (not null) for an absent model/id; normalize to null.
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
