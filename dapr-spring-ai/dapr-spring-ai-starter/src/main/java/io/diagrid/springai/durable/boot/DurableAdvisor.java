package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentResult;
import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import io.diagrid.springai.durable.workflow.TokenUsage;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a {@code ChatClient.call()} durable by running it as a Dapr Workflow instead of invoking the
 * model in-process.
 *
 * <p><b>Order.</b> Runs just before the terminal model-call advisor (at {@link Ordered#LOWEST_PRECEDENCE}):
 * late enough that the request it captures already has memory/RAG context and the tools that Spring
 * AI's {@code ToolCallingAdvisor} (and request building) merged into the prompt options, but strictly
 * before the terminal advisor so this one reliably runs and short-circuits it. It does NOT call the
 * rest of the chain, so it is itself <b>terminal</b>: any non-terminal advisor ordered after it will
 * never run. The advisor logs a one-time WARN naming any such stranded advisors (the framework's own
 * {@link ChatModelCallAdvisor}, which this one deliberately replaces, is not flagged). The
 * <b>generic</b> instance (added to every ChatClient) runs at {@code LOWEST_PRECEDENCE - 1} and is
 * named {@code DaprDurableAdvisor}; a <b>per-agent</b> instance (added to a named ChatClient bean,
 * running under a workflow named after the bean) runs at {@code LOWEST_PRECEDENCE - 2} so it wins over
 * the generic one on the same client, and is named {@code DaprDurableAdvisor[<workflowName>]} for
 * traceability.
 *
 * <p><b>Tools.</b> The advertised tool surface is the union of (a) globally discovered {@code @Tool}
 * beans and (b) the request-scoped tools attached to this specific ChatClient via
 * {@code .defaultTools(...)} / {@code .tools(...)} — read from the prompt's
 * {@link ToolCallingChatOptions#getToolCallbacks()}. Request tools win on name collisions, and are
 * registered into the {@link io.diagrid.springai.durable.workflow.ToolRegistry} so the workflow's
 * tool activity can execute them. This preserves per-agent scoping: an agent is only offered its own
 * tools plus any global ones.
 *
 * <p><b>Durability caveat.</b> Request-scoped tools that are not Spring beans (e.g.
 * {@code .defaultTools(new WeatherTools())}) are registered in memory at call time; they work on the
 * live path but are NOT re-registered after a cold worker restart (unlike {@code @Tool} beans, which
 * are rediscovered at startup). For full crash-recovery of tool steps, back tools with {@code @Tool}
 * Spring beans.
 */
public final class DurableAdvisor implements CallAdvisor {

  private static final Logger LOG = LoggerFactory.getLogger(DurableAdvisor.class);

  /** Base advisor name; per-agent instances append {@code [<workflowName>]}. */
  static final String NAME_PREFIX = "DaprDurableAdvisor";

  private final DurableRunner runner;
  private final DiscoveredTools tools;
  private final MessageCodec codec;
  private final String workflowName;
  private final int order;

  /** Guards the one-time shadowed-advisor WARN. */
  private volatile boolean shadowingChecked;

  /** Generic advisor for every ChatClient: runs the shared {@link AgentWorkflow} type. */
  public DurableAdvisor(DurableRunner runner, DiscoveredTools tools, MessageCodec codec) {
    this(runner, tools, codec, AgentWorkflow.NAME, Ordered.LOWEST_PRECEDENCE - 1);
  }

  /**
   * Per-agent advisor for a named ChatClient bean: runs a workflow named after the bean, at higher
   * precedence so it wins over the generic advisor present on the same client.
   *
   * @param workflowName the per-agent workflow name (the ChatClient bean name)
   */
  public DurableAdvisor(DurableRunner runner, DiscoveredTools tools, MessageCodec codec, String workflowName) {
    this(runner, tools, codec, workflowName, Ordered.LOWEST_PRECEDENCE - 2);
  }

  private DurableAdvisor(
      DurableRunner runner, DiscoveredTools tools, MessageCodec codec, String workflowName, int order) {
    this.runner = runner;
    this.tools = tools;
    this.codec = codec;
    this.workflowName = workflowName;
    this.order = order;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    warnIfShadowingOnce(chain);
    String conversationId = conversationId(request);
    List<ToolSpec> toolSpecs = resolveToolSpecs(request.prompt().getOptions());
    ChatOptionsSpec options = ChatOptionsSpec.from(request.prompt().getOptions());

    AgentRequest agentRequest =
        new AgentRequest(
            codec.toRecords(request.prompt().getInstructions()), toolSpecs, options, conversationId);

    AgentResult result;
    try {
      result = runner.run(agentRequest, workflowName);
    } catch (Exception e) {
      throw new IllegalStateException("Durable ChatClient call failed", e);
    }

    return new ChatClientResponse(toChatResponse(result), new HashMap<>(request.context()));
  }

  /**
   * Rebuilds a Spring AI {@link ChatResponse} from the workflow's {@link AgentResult} so upstream
   * advisors and user code see real response metadata: the finish reason on the generation, and the
   * aggregated usage + model + id on the response metadata.
   */
  private static ChatResponse toChatResponse(AgentResult result) {
    ChatGenerationMetadata generationMetadata =
        result.finishReason() == null
            ? ChatGenerationMetadata.NULL
            : ChatGenerationMetadata.builder().finishReason(result.finishReason()).build();
    Generation generation =
        new Generation(new AssistantMessage(result.finalText()), generationMetadata);

    ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
    if (result.model() != null) {
      metadata.model(result.model());
    }
    if (result.responseId() != null) {
      metadata.id(result.responseId());
    }
    TokenUsage usage = result.aggregatedUsage();
    if (usage != null) {
      metadata.usage(
          new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
    }
    return new ChatResponse(List.of(generation), metadata.build());
  }

  /**
   * Union of globally discovered tools and this request's tools (request wins by name). Request tool
   * callbacks are registered into the shared registry so the workflow's tool activity can run them.
   *
   * <p>Reading {@link ToolCallingChatOptions#getToolCallbacks()} is the complete request tool surface
   * in Spring AI 2.0 GA: {@code ChatClient.Builder} exposes only {@code defaultTools(Object...)} (the
   * string-name {@code getToolNames()} API of the M-series was removed), and tool objects,
   * {@code ToolCallback}s, and {@code ToolCallbackProvider}s (e.g. MCP) are all resolved to callbacks
   * before the call — so there is no separate by-name resolution to do here.
   */
  private List<ToolSpec> resolveToolSpecs(ChatOptions chatOptions) {
    LinkedHashMap<String, ToolSpec> byName = new LinkedHashMap<>();
    for (ToolSpec spec : tools.specs()) {
      byName.put(spec.name(), spec);
    }
    if (chatOptions instanceof ToolCallingChatOptions toolOptions) {
      // getToolCallbacks() can return null (not an empty list) for a ChatClient built with no
      // tools — e.g. an LLM-only synthesis agent. Treat null as no request-scoped tools.
      List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
      if (callbacks != null) {
        for (ToolCallback callback : callbacks) {
          ToolDefinition definition = callback.getToolDefinition();
          tools.registry().register(definition.name(), callback::call);
          byName.put(
              definition.name(),
              new ToolSpec(definition.name(), definition.description(), definition.inputSchema()));
          LOG.debug("Advertising request-scoped tool '{}' to the durable workflow", definition.name());
        }
      }
    }
    return List.copyOf(byName.values());
  }

  private static String conversationId(ChatClientRequest request) {
    Object value = request.context().get(ChatMemory.CONVERSATION_ID);
    return value == null ? null : value.toString();
  }

  @Override
  public String getName() {
    // Generic instance keeps the base name; per-agent instances append the workflow name so multiple
    // durable advisors on one chain are distinguishable in traces (and to avoid advisor-name dedup).
    return AgentWorkflow.NAME.equals(workflowName) ? NAME_PREFIX : NAME_PREFIX + "[" + workflowName + "]";
  }

  @Override
  public int getOrder() {
    // Just before the terminal ChatModelCallAdvisor (LOWEST_PRECEDENCE), after the ToolCallingAdvisor
    // + request building. Per-agent advisors sit one step earlier so they win over the generic one.
    return order;
  }

  // The durable advisor is terminal: it does not call chain.nextCall(), so any advisor ordered after
  // it never runs — except the framework's ChatModelCallAdvisor, which this advisor deliberately
  // replaces. Warn once per instance if a user advisor is stranded behind it.
  private void warnIfShadowingOnce(CallAdvisorChain chain) {
    if (chain == null || shadowingChecked) {
      return;
    }
    shadowingChecked = true;
    List<String> shadowed = shadowedAdvisorNames(chain.getCallAdvisors());
    if (!shadowed.isEmpty()) {
      LOG.warn(
          "Durable advisor '{}' is terminal (it short-circuits the chain), so these advisors ordered "
              + "after it will never run: {}. Give them a lower order (higher precedence) than the "
              + "durable advisor if they must execute.",
          getName(), shadowed);
    }
  }

  /** Names of advisors ordered strictly after this one, excluding our own kind and the terminal. */
  List<String> shadowedAdvisorNames(List<CallAdvisor> advisors) {
    List<String> names = new ArrayList<>();
    for (CallAdvisor advisor : advisors) {
      if (advisor instanceof DurableAdvisor || advisor instanceof ChatModelCallAdvisor) {
        continue;
      }
      if (advisor.getOrder() > order) {
        names.add(advisor.getName());
      }
    }
    return names;
  }
}
