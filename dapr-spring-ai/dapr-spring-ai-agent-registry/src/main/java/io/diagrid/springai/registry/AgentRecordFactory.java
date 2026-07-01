package io.diagrid.springai.registry;

import io.diagrid.springai.registry.model.AgentMetadata;
import io.diagrid.springai.registry.model.AgentMetadataSchema;
import io.diagrid.springai.registry.model.LlmMetadata;
import io.diagrid.springai.registry.model.ToolMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Builds an {@link AgentMetadataSchema} from a live {@code ChatClient} call. The system prompt, tool
 * surface and resolved model are read off the actual request, so the registered record reflects how
 * the agent is really configured rather than a guess made at startup.
 */
public final class AgentRecordFactory {

  private static final String VERSION = "0.1.0";
  private static final String TYPE_DURABLE = "DurableAgent";
  private static final String TYPE_STANDARD = "Agent";
  private static final String FRAMEWORK = "spring-ai";
  // Per-agent workflow name convention, mirrored from the durability starter's
  // DurableChatClientBeanPostProcessor so consumers can correlate an agent to its workflow.
  private static final String WORKFLOW_NAME_PREFIX = "spring-ai.";
  private static final String WORKFLOW_NAME_SUFFIX = ".workflow";
  private static final String WORKFLOW_NAME_KEY = "workflow_name";

  private final String appId;
  private final String llmClient;
  private final String llmProvider;
  private final String defaultModel;

  /**
   * @param appId        Dapr application id recorded on every agent
   * @param llmClient    ChatModel client class name (e.g. {@code OllamaChatModel})
   * @param llmProvider  inferred provider (e.g. {@code ollama})
   * @param defaultModel model used when a call sets no explicit model
   */
  public AgentRecordFactory(String appId, String llmClient, String llmProvider, String defaultModel) {
    this.appId = appId;
    this.llmClient = llmClient;
    this.llmProvider = llmProvider;
    this.defaultModel = defaultModel;
  }

  /**
   * Builds the thin record known at startup (no live call): name, app id and model only.
   *
   * @param name    agent name (the ChatClient bean name)
   * @param durable whether the agent runs under the Dapr durability layer
   * @return the agent record to register eagerly
   */
  public AgentMetadataSchema buildThin(String name, boolean durable) {
    Map<String, Object> extras = durable ? Map.of(WORKFLOW_NAME_KEY, workflowName(name)) : null;
    AgentMetadata agent =
        new AgentMetadata(appId, type(durable), null, null, null, null, FRAMEWORK, extras);
    LlmMetadata llm = new LlmMetadata(llmClient, llmProvider, "chat", defaultModel);
    return new AgentMetadataSchema(VERSION, name, Instant.now().toString(), agent, llm, null);
  }

  /**
   * Builds the full record from a live call, enriching it with the system prompt and advertised tools.
   *
   * @param name    agent name (the ChatClient bean name)
   * @param request the call being intercepted
   * @param durable whether the agent runs under the Dapr durability layer
   * @return the agent record to register
   */
  public AgentMetadataSchema build(String name, ChatClientRequest request, boolean durable) {
    ChatOptions options = request.prompt().getOptions();
    Map<String, Object> extras = durable ? Map.of(WORKFLOW_NAME_KEY, workflowName(name)) : null;
    AgentMetadata agent = new AgentMetadata(
        appId, type(durable), null, null, null, systemPrompt(request), FRAMEWORK, extras);
    LlmMetadata llm = new LlmMetadata(llmClient, llmProvider, "chat", resolveModel(options));
    List<ToolMetadata> tools = tools(options);
    return new AgentMetadataSchema(
        VERSION, name, Instant.now().toString(), agent, llm, tools.isEmpty() ? null : tools);
  }

  private static String type(boolean durable) {
    return durable ? TYPE_DURABLE : TYPE_STANDARD;
  }

  // The per-agent workflow name (spring-ai.{name}.workflow), recorded for a durable agent so
  // consumers can correlate the agent to its workflow.
  private static String workflowName(String name) {
    return WORKFLOW_NAME_PREFIX + name + WORKFLOW_NAME_SUFFIX;
  }

  private String resolveModel(ChatOptions options) {
    if (options != null && options.getModel() != null) {
      return options.getModel();
    }
    return defaultModel;
  }

  private static String systemPrompt(ChatClientRequest request) {
    StringBuilder sb = new StringBuilder();
    for (Message message : request.prompt().getInstructions()) {
      if (message instanceof SystemMessage system) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append(system.getText());
      }
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private static List<ToolMetadata> tools(ChatOptions options) {
    if (!(options instanceof ToolCallingChatOptions toolOptions)) {
      return List.of();
    }
    List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
    if (callbacks == null || callbacks.isEmpty()) {
      return List.of();
    }
    List<ToolMetadata> tools = new ArrayList<>(callbacks.size());
    for (ToolCallback callback : callbacks) {
      ToolDefinition definition = callback.getToolDefinition();
      tools.add(new ToolMetadata(definition.name(), definition.description(), definition.inputSchema()));
    }
    return tools;
  }
}
