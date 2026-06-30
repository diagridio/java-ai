package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * <p><b>Order.</b> Runs at {@link Ordered#LOWEST_PRECEDENCE} - 1: late enough that the request it
 * captures already has memory/RAG context and the tools that Spring AI's {@code ToolCallingAdvisor}
 * (and request building) merged into the prompt options, but strictly before the terminal
 * model-call advisor (which is at LOWEST_PRECEDENCE) so this advisor reliably runs and short-circuits
 * it. It does NOT call the rest of the chain.
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

  private final DurableRunner runner;
  private final DiscoveredTools tools;
  private final MessageCodec codec;

  public DurableAdvisor(DurableRunner runner, DiscoveredTools tools, MessageCodec codec) {
    this.runner = runner;
    this.tools = tools;
    this.codec = codec;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    String conversationId = conversationId(request);
    List<ToolSpec> toolSpecs = resolveToolSpecs(request.prompt().getOptions());
    Map<String, Object> options = options(request.prompt().getOptions());

    AgentRequest agentRequest =
        new AgentRequest(
            codec.toRecords(request.prompt().getInstructions()), toolSpecs, options, conversationId);

    String finalText;
    try {
      finalText = runner.run(agentRequest);
    } catch (Exception e) {
      throw new IllegalStateException("Durable ChatClient call failed", e);
    }

    ChatResponse chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage(finalText))));
    return new ChatClientResponse(chatResponse, new HashMap<>(request.context()));
  }

  /**
   * Union of globally discovered tools and this request's tools (request wins by name). Request tool
   * callbacks are registered into the shared registry so the workflow's tool activity can run them.
   */
  private List<ToolSpec> resolveToolSpecs(ChatOptions chatOptions) {
    LinkedHashMap<String, ToolSpec> byName = new LinkedHashMap<>();
    for (ToolSpec spec : tools.specs()) {
      byName.put(spec.name(), spec);
    }
    if (chatOptions instanceof ToolCallingChatOptions toolOptions) {
      for (ToolCallback callback : toolOptions.getToolCallbacks()) {
        ToolDefinition definition = callback.getToolDefinition();
        tools.registry().register(definition.name(), callback::call);
        byName.put(
            definition.name(),
            new ToolSpec(definition.name(), definition.description(), definition.inputSchema()));
        LOG.debug("Advertising request-scoped tool '{}' to the durable workflow", definition.name());
      }
    }
    return List.copyOf(byName.values());
  }

  private static String conversationId(ChatClientRequest request) {
    Object value = request.context().get(ChatMemory.CONVERSATION_ID);
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> options(ChatOptions chatOptions) {
    Map<String, Object> options = new HashMap<>();
    if (chatOptions != null) {
      if (chatOptions.getModel() != null) {
        options.put("model", chatOptions.getModel());
      }
      if (chatOptions.getTemperature() != null) {
        options.put("temperature", chatOptions.getTemperature());
      }
    }
    return options;
  }

  @Override
  public String getName() {
    return "DaprDurableAdvisor";
  }

  @Override
  public int getOrder() {
    // Strictly before the terminal ChatModelCallAdvisor (LOWEST_PRECEDENCE); after the
    // ToolCallingAdvisor + request building that merge tools into the prompt options.
    return Ordered.LOWEST_PRECEDENCE - 1;
  }
}
