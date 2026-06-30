package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;

/**
 * Makes a {@code ChatClient.call()} durable by running it as a Dapr Workflow instead of invoking the
 * model in-process.
 *
 * <p>Runs LAST in the advisor chain ({@link Ordered#LOWEST_PRECEDENCE}) so the request it captures
 * already includes anything earlier advisors injected (memory history, RAG context). It does NOT
 * call the rest of the chain: it derives the durable instance id from the request, schedules/attaches
 * the workflow (which runs the model-and-tools loop as activities), blocks on completion, and returns
 * the final assistant message. The user's ChatClient code is unchanged.
 */
public final class DurableAdvisor implements CallAdvisor {

  private final DurableRunner runner;
  private final Supplier<List<ToolSpec>> toolSpecs;
  private final MessageCodec codec;

  /**
   * @param toolSpecs supplies the advertised tool surface at call time (tools are discovered after
   *                  context startup, so this must be read per call, not captured at construction)
   */
  public DurableAdvisor(DurableRunner runner, Supplier<List<ToolSpec>> toolSpecs, MessageCodec codec) {
    this.runner = runner;
    this.toolSpecs = toolSpecs;
    this.codec = codec;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    String conversationId = conversationId(request);
    Map<String, Object> options = options(request.prompt().getOptions());

    AgentRequest agentRequest =
        new AgentRequest(
            codec.toRecords(request.prompt().getInstructions()),
            toolSpecs.get(),
            options,
            conversationId);

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
    return Ordered.LOWEST_PRECEDENCE;
  }
}
