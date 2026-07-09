package io.diagrid.springai.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dapr.client.DaprPreviewClient;
import io.dapr.client.domain.ConversationInputAlpha2;
import io.dapr.client.domain.ConversationMessage;
import io.dapr.client.domain.ConversationMessageContent;
import io.dapr.client.domain.ConversationRequestAlpha2;
import io.dapr.client.domain.ConversationResponseAlpha2;
import io.dapr.client.domain.ConversationResultAlpha2;
import io.dapr.client.domain.ConversationResultChoices;
import io.dapr.client.domain.ConversationResultCompletionUsage;
import io.dapr.client.domain.ConversationResultMessage;
import io.dapr.client.domain.ConversationToolCalls;
import io.dapr.client.domain.ConversationToolCallsOfFunction;
import io.dapr.client.domain.ConversationTools;
import io.dapr.client.domain.ConversationToolsFunction;
import io.dapr.client.domain.ToolMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A Spring AI {@link ChatModel} whose LLM calls go through the Dapr sidecar's Conversation building
 * block instead of a provider SDK: swap providers by component configuration, keep provider keys out
 * of the app, and get sidecar features like PII scrubbing for free.
 *
 * <p><b>Capability statement</b> (verified against the pinned dapr-sdk 1.18.0 sources, which speak
 * the <em>alpha2</em> conversation API via {@link DaprPreviewClient#converseAlpha2}):
 *
 * <ul>
 *   <li><b>Messages/roles:</b> system, user, assistant, and tool messages map one-to-one onto the
 *       alpha2 message types (a Spring AI {@code ToolResponseMessage} fans out to one Dapr tool
 *       message per tool response). Content is <b>text only</b> — a message carrying media fails
 *       fast rather than silently dropping it.
 *   <li><b>Tool calling:</b> supported. Tool definitions from {@link ToolCallingChatOptions} are
 *       advertised on the request; tool calls returned by the model are mapped onto
 *       {@link AssistantMessage#getToolCalls()}. This model <b>never executes</b> a
 *       {@link ToolCallback} — on the durable path tools run as Dapr workflow activities, and the
 *       definition-only callbacks attached there throw if invoked.
 *   <li><b>Parameters:</b> {@code temperature} is the only portable {@link ChatOptions} field the
 *       alpha2 request carries; other sampling options (model, maxTokens, topP, …) are ignored with
 *       a warning — the model and its parameters are chosen by the Dapr component configuration.
 *       Note the pinned SDK transmits temperature unconditionally, so an unset temperature reaches
 *       the provider as an explicit {@code 0.0}. Dapr-specific knobs configured on this model:
 *       {@code contextId} (conversation session id) and {@code scrubPii} (obfuscate PII in the
 *       sidecar). The API also defines tool-choice, per-request parameters/metadata, JSON-schema
 *       response format, and prompt-cache retention, which this module does not surface yet.
 *   <li><b>Streaming:</b> none — the alpha2 API is single-response, so this model implements only
 *       {@code call}; {@code stream} keeps Spring AI's default throwing behavior.
 *   <li><b>Response metadata:</b> per-choice finish reason, plus model name and token usage
 *       (prompt/completion/total) when the component reports them; the response's Dapr context id is
 *       exposed under the {@code "dapr-context-id"} metadata key. The API returns no response id.
 * </ul>
 */
public final class DaprConversationChatModel implements ChatModel {

  private static final Logger LOG = LoggerFactory.getLogger(DaprConversationChatModel.class);

  /** Metadata key under which the response's Dapr conversation context id is exposed. */
  public static final String CONTEXT_ID_METADATA_KEY = "dapr-context-id";

  private final DaprPreviewClient client;
  private final String component;
  private final String contextId;
  private final boolean scrubPii;
  private final Double temperature;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DaprConversationChatModel(DaprPreviewClient client, String component) {
    this(client, component, null, false, null);
  }

  /**
   * @param client      Dapr client exposing the Conversation API
   * @param component   name of the Dapr conversation component to route calls to (required)
   * @param contextId   conversation session id handed to the sidecar, or null
   * @param scrubPii    whether the sidecar obfuscates PII in inputs and outputs
   * @param temperature default sampling temperature, or null (see class Javadoc: the SDK then
   *                    transmits 0.0)
   */
  public DaprConversationChatModel(DaprPreviewClient client, String component, String contextId,
      boolean scrubPii, Double temperature) {
    if (client == null) {
      throw new IllegalArgumentException("A DaprPreviewClient is required.");
    }
    if (component == null || component.isBlank()) {
      throw new IllegalArgumentException(
          "A Dapr conversation component name is required: it names the sidecar component that "
              + "routes LLM traffic (e.g. a conversation.openai or conversation.echo component).");
    }
    this.client = client;
    this.component = component;
    this.contextId = contextId;
    this.scrubPii = scrubPii;
    this.temperature = temperature;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    List<ConversationMessage> messages =
        prompt.getInstructions().stream().flatMap(this::toConversationMessages).toList();

    // The alpha2 API scrubs PII in two independent places: the input flag covers prompt content
    // before it reaches the provider, the request flag covers what the LLM returns. Set both.
    ConversationRequestAlpha2 request = new ConversationRequestAlpha2(component,
        List.of(new ConversationInputAlpha2(messages).setScrubPii(scrubPii)));
    request.setScrubPii(scrubPii);
    if (contextId != null) {
      request.setContextId(contextId);
    }
    applyOptions(prompt.getOptions(), request);

    ConversationResponseAlpha2 response = client.converseAlpha2(request).block();
    if (response == null) {
      throw new IllegalStateException(
          "Dapr conversation component '" + component + "' returned no response.");
    }
    return toChatResponse(response);
  }

  /**
   * Default options for this model. Returns a {@link ToolCallingChatOptions} so that generic
   * callers (like the durability layer's options factory) can attach tool callbacks to a mutation
   * of these defaults.
   */
  @Override
  public ChatOptions getOptions() {
    return ToolCallingChatOptions.builder().temperature(temperature).build();
  }

  // --- request mapping ---

  private Stream<ConversationMessage> toConversationMessages(Message message) {
    return switch (message.getMessageType()) {
      case SYSTEM -> Stream.of(new io.dapr.client.domain.SystemMessage(contentOf(message.getText())));
      case USER -> Stream.of(toUserMessage((UserMessage) message));
      case ASSISTANT -> Stream.of(toAssistantMessage((AssistantMessage) message));
      case TOOL -> toToolMessages((ToolResponseMessage) message);
    };
  }

  private ConversationMessage toUserMessage(UserMessage message) {
    failOnMedia(message.getMedia(), "UserMessage");
    return new io.dapr.client.domain.UserMessage(contentOf(message.getText()));
  }

  private ConversationMessage toAssistantMessage(AssistantMessage message) {
    failOnMedia(message.getMedia(), "AssistantMessage");
    List<ConversationToolCalls> toolCalls =
        message.getToolCalls().stream()
            .map(tc -> new ConversationToolCalls(
                new ConversationToolCallsOfFunction(tc.name(), tc.arguments())).setId(tc.id()))
            .toList();
    return new io.dapr.client.domain.AssistantMessage(contentOf(message.getText()), toolCalls);
  }

  // One Spring AI ToolResponseMessage carries all tool results of a turn; the alpha2 API wants one
  // tool message per result.
  private Stream<ConversationMessage> toToolMessages(ToolResponseMessage message) {
    return message.getResponses().stream()
        .map(r -> (ConversationMessage) new ToolMessage(contentOf(r.responseData()))
            .setToolId(r.id())
            .setName(r.name()));
  }

  private static List<ConversationMessageContent> contentOf(String text) {
    return text == null ? List.of() : List.of(new ConversationMessageContent(text));
  }

  private static void failOnMedia(List<?> media, String messageType) {
    if (media != null && !media.isEmpty()) {
      throw new IllegalStateException(
          "The Dapr Conversation API is text-only: this " + messageType + " carries "
              + media.size() + " media item(s) that would be dropped. Send a text-only prompt, or "
              + "use a provider ChatModel for multimodal calls.");
    }
  }

  private void applyOptions(ChatOptions options, ConversationRequestAlpha2 request) {
    Double effectiveTemperature =
        options != null && options.getTemperature() != null ? options.getTemperature() : temperature;
    if (effectiveTemperature != null) {
      request.setTemperature(effectiveTemperature);
    }
    if (options == null) {
      return;
    }
    warnUnsupportedOptions(options);
    if (options instanceof ToolCallingChatOptions toolOptions) {
      List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
      if (callbacks != null && !callbacks.isEmpty()) {
        request.setTools(callbacks.stream().map(this::toTool).toList());
      }
    }
  }

  // Advertise the tool definition only. The callback itself is never invoked here: on the durable
  // path tools execute as Dapr workflow activities, and the attached callbacks throw if called.
  private ConversationTools toTool(ToolCallback callback) {
    ToolDefinition definition = callback.getToolDefinition();
    String schema = definition.inputSchema();
    // A null parameters map means "no schema" to the SDK, which then omits the proto field.
    ConversationToolsFunction function = new ConversationToolsFunction(definition.name(),
        schema == null || schema.isBlank() ? null : parseSchema(definition));
    if (definition.description() != null && !definition.description().isBlank()) {
      function.setDescription(definition.description());
    }
    return new ConversationTools(function);
  }

  // Spring AI carries the input schema as a JSON string; the Dapr SDK wants it as a Map it can
  // convert to a protobuf Struct.
  private Map<String, Object> parseSchema(ToolDefinition definition) {
    String schema = definition.inputSchema();
    try {
      return objectMapper.readValue(schema, new TypeReference<Map<String, Object>>() { });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Tool '" + definition.name() + "' has an input schema that is not valid JSON, so it "
              + "cannot be advertised to the Dapr Conversation API: " + schema, e);
    }
  }

  private void warnUnsupportedOptions(ChatOptions options) {
    List<String> dropped = new ArrayList<>();
    if (options.getModel() != null) {
      dropped.add("model");
    }
    if (options.getMaxTokens() != null) {
      dropped.add("maxTokens");
    }
    if (options.getTopP() != null) {
      dropped.add("topP");
    }
    if (options.getTopK() != null) {
      dropped.add("topK");
    }
    if (options.getFrequencyPenalty() != null) {
      dropped.add("frequencyPenalty");
    }
    if (options.getPresencePenalty() != null) {
      dropped.add("presencePenalty");
    }
    if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
      dropped.add("stopSequences");
    }
    if (!dropped.isEmpty()) {
      LOG.warn("Ignoring ChatOptions {} unsupported by the Dapr Conversation API (alpha2 carries "
          + "only temperature); the model and sampling parameters come from the '{}' component "
          + "configuration.", dropped, component);
    }
  }

  // --- response mapping ---

  private ChatResponse toChatResponse(ConversationResponseAlpha2 response) {
    List<Generation> generations = new ArrayList<>();
    String model = null;
    long promptTokens = 0;
    long completionTokens = 0;
    long totalTokens = 0;
    boolean hasUsage = false;

    for (ConversationResultAlpha2 output : response.getOutputs()) {
      if (model == null && output.getModel() != null && !output.getModel().isBlank()) {
        model = output.getModel();
      }
      ConversationResultCompletionUsage usage = output.getUsage();
      if (usage != null) {
        hasUsage = true;
        promptTokens += usage.getPromptTokens();
        completionTokens += usage.getCompletionTokens();
        totalTokens += usage.getTotalTokens();
      }
      for (ConversationResultChoices choice : output.getChoices()) {
        generations.add(toGeneration(choice));
      }
    }
    if (generations.isEmpty()) {
      throw new IllegalStateException(
          "Dapr conversation component '" + component + "' returned no choices.");
    }

    ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
    if (model != null) {
      metadata.model(model);
    }
    if (hasUsage) {
      metadata.usage(new DefaultUsage(
          Math.toIntExact(promptTokens), Math.toIntExact(completionTokens), Math.toIntExact(totalTokens)));
    }
    if (response.getContextId() != null && !response.getContextId().isBlank()) {
      metadata.keyValue(CONTEXT_ID_METADATA_KEY, response.getContextId());
    }
    return new ChatResponse(generations, metadata.build());
  }

  private Generation toGeneration(ConversationResultChoices choice) {
    ConversationResultMessage message = choice.getMessage();
    List<AssistantMessage.ToolCall> toolCalls =
        message.hasToolCalls()
            ? message.getToolCalls().stream()
                .map(tc -> new AssistantMessage.ToolCall(
                    tc.getId(), "function", tc.getFunction().getName(), tc.getFunction().getArguments()))
                .toList()
            : List.<AssistantMessage.ToolCall>of();
    AssistantMessage assistant =
        AssistantMessage.builder().content(message.getContent()).toolCalls(toolCalls).build();
    ChatGenerationMetadata generationMetadata =
        ChatGenerationMetadata.builder().finishReason(choice.getFinishReason()).build();
    return new Generation(assistant, generationMetadata);
  }
}
