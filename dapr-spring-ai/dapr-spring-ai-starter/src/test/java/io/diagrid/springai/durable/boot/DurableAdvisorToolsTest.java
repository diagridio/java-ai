package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;

/**
 * Verifies the bug fix: {@link DurableAdvisor} advertises request-scoped tools (from the prompt's
 * {@code ToolCallingChatOptions.getToolCallbacks()}) merged with globally discovered {@code @Tool}
 * beans, and registers the request callbacks so the workflow can execute them.
 */
class DurableAdvisorToolsTest {

  /** A request-scoped tool, supplied per-ChatClient (e.g. via {@code .defaultTools(new WeatherTools())}). */
  static class WeatherTools {
    @Tool(description = "Get the current weather for a city")
    public String getWeather(String city) {
      return city + ": 22C, partly cloudy";
    }
  }

  /** A global @Tool bean, discovered at startup. */
  static class CurrencyTools {
    @Tool(description = "Convert an amount between currencies")
    public String convert(String query) {
      return "42 USD";
    }
  }

  /** Captures the AgentRequest (and workflow name) the advisor would hand to the workflow. */
  static class CapturingRunner extends DurableRunner {
    AgentRequest captured;
    String capturedWorkflowName;

    CapturingRunner() {
      super(null, new InstanceIdDerivation(), Duration.ofSeconds(1));
    }

    @Override
    public String run(AgentRequest request, String workflowName) {
      this.captured = request;
      this.capturedWorkflowName = workflowName;
      return "FINAL";
    }
  }

  private DiscoveredTools globalToolsWithCurrency() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(CurrencyTools.class);
    context.refresh();
    DiscoveredTools tools = new DiscoveredTools();
    tools.populate(context);
    return tools;
  }

  private ChatClientRequest requestWithTools(Object... toolObjects) {
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(toolObjects)).build();
    Prompt prompt = new Prompt(List.of(new UserMessage("what is the weather in Paris?")), options);
    return new ChatClientRequest(prompt, new HashMap<>());
  }

  @Test
  void perAgentAdvisorUsesBeanNameAndOutranksGeneric() {
    CapturingRunner runner = new CapturingRunner();
    DurableAdvisor generic = new DurableAdvisor(runner, new DiscoveredTools(), new MessageCodec());
    DurableAdvisor perAgent =
        new DurableAdvisor(runner, new DiscoveredTools(), new MessageCodec(), "weatherAssistant");

    generic.adviseCall(requestWithTools(), null);
    assertEquals(AgentWorkflow.NAME, runner.capturedWorkflowName, "generic advisor uses the shared name");
    assertEquals(Ordered.LOWEST_PRECEDENCE - 1, generic.getOrder());

    perAgent.adviseCall(requestWithTools(), null);
    assertEquals("weatherAssistant", runner.capturedWorkflowName, "per-agent advisor uses the given name");
    assertEquals(Ordered.LOWEST_PRECEDENCE - 2, perAgent.getOrder());
    assertTrue(perAgent.getOrder() < generic.getOrder(), "per-agent advisor must win by precedence");

    assertEquals("DaprDurableAdvisor", generic.getName(), "generic advisor keeps the base name");
    assertEquals("DaprDurableAdvisor[weatherAssistant]", perAgent.getName(),
        "per-agent advisor name includes the workflow name for traceability");

    assertEquals("spring-ai.weatherAssistant.workflow",
        DurableChatClientBeanPostProcessor.workflowName("weatherAssistant"),
        "per-agent workflow name follows the dapr-agents convention (contains .workflow)");
  }

  @Test
  void requestScopedToolIsAdvertisedExecutableAndMergedWithGlobal() {
    DiscoveredTools tools = globalToolsWithCurrency();
    CapturingRunner runner = new CapturingRunner();
    DurableAdvisor advisor = new DurableAdvisor(runner, tools, new MessageCodec());

    String result = advisor.adviseCall(requestWithTools(new WeatherTools()), null).chatResponse()
        .getResult().getOutput().getText();
    assertEquals("FINAL", result);

    List<String> advertised = runner.captured.toolSpecs().stream().map(ToolSpec::name).toList();
    assertTrue(advertised.contains("getWeather"), "request-scoped tool must be advertised: " + advertised);
    assertTrue(advertised.contains("convert"), "global @Tool bean must still be advertised: " + advertised);

    // The request tool must be executable by the workflow's tool activity (registered by name).
    // (Argument binding is Spring AI's concern; here we only assert the call routes and runs.)
    assertTrue(tools.registry().has("getWeather"));
    assertTrue(
        tools.registry().invoke("getWeather", "{\"city\":\"Paris\"}").contains("22C, partly cloudy"),
        "request tool must be executable via the registry");
  }

  @Test
  void withoutRequestToolsOnlyGlobalToolsAreAdvertised() {
    DiscoveredTools tools = globalToolsWithCurrency();
    CapturingRunner runner = new CapturingRunner();
    DurableAdvisor advisor = new DurableAdvisor(runner, tools, new MessageCodec());

    advisor.adviseCall(requestWithTools(), null); // no request-scoped tools

    List<String> advertised = runner.captured.toolSpecs().stream().map(ToolSpec::name).toList();
    assertTrue(advertised.contains("convert"));
    assertFalse(advertised.contains("getWeather"), "an agent that did not register WeatherTools must not be offered it");
  }

  /**
   * Fidelity: the whole portable ChatOptions surface (not just model + temperature) is captured and
   * carried to the workflow, so options like maxTokens/topP/stopSequences are not silently dropped.
   */
  @Test
  void fullPortableChatOptionsAreCarriedToTheWorkflow() {
    DiscoveredTools tools = new DiscoveredTools();
    CapturingRunner runner = new CapturingRunner();
    DurableAdvisor advisor = new DurableAdvisor(runner, tools, new MessageCodec());

    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder()
            .model("gpt-4o")
            .temperature(0.3)
            .maxTokens(512)
            .topP(0.9)
            .topK(40)
            .frequencyPenalty(0.5)
            .presencePenalty(0.25)
            .stopSequences(List.of("STOP"))
            .build();
    Prompt prompt = new Prompt(List.of(new UserMessage("plan a trip")), options);

    advisor.adviseCall(new ChatClientRequest(prompt, new HashMap<>()), null);

    ChatOptionsSpec spec = runner.captured.options();
    assertEquals("gpt-4o", spec.model());
    assertEquals(Double.valueOf(0.3), spec.temperature());
    assertEquals(Integer.valueOf(512), spec.maxTokens());
    assertEquals(Double.valueOf(0.9), spec.topP());
    assertEquals(Integer.valueOf(40), spec.topK());
    assertEquals(Double.valueOf(0.5), spec.frequencyPenalty());
    assertEquals(Double.valueOf(0.25), spec.presencePenalty());
    assertEquals(List.of("STOP"), spec.stopSequences());
  }

  /** Regression: a tool-less agent's options return null (not []) from getToolCallbacks(). */
  @Test
  void nullToolCallbacksAdvertisesNoToolsWithoutNpe() {
    DiscoveredTools tools = new DiscoveredTools(); // no global @Tool beans, like ItineraryFormatter
    CapturingRunner runner = new CapturingRunner();
    DurableAdvisor advisor = new DurableAdvisor(runner, tools, new MessageCodec());

    Prompt prompt = new Prompt(List.of(new UserMessage("synthesize the itinerary")), new NullToolsOptions());
    ChatClientRequest request = new ChatClientRequest(prompt, new HashMap<>());

    // Must not NPE despite getToolCallbacks() == null.
    String result =
        advisor.adviseCall(request, null).chatResponse().getResult().getOutput().getText();
    assertEquals("FINAL", result);
    assertTrue(runner.captured.toolSpecs().isEmpty(), "a tool-less agent must advertise zero tools");
  }

  @Test
  void shadowedAdvisorNamesFlagsStrandedAdvisorsButNotOwnKind() {
    CapturingRunner runner = new CapturingRunner();
    // Per-agent advisor at LOWEST_PRECEDENCE-2; the generic durable advisor at LOWEST_PRECEDENCE-1
    // sits after it but is our own kind, so it must not be flagged.
    DurableAdvisor perAgent =
        new DurableAdvisor(runner, new DiscoveredTools(), new MessageCodec(), "weatherAssistant");
    DurableAdvisor generic = new DurableAdvisor(runner, new DiscoveredTools(), new MessageCodec());
    CallAdvisor before = advisorAt("memory", Ordered.HIGHEST_PRECEDENCE);
    CallAdvisor stranded = advisorAt("logging", Ordered.LOWEST_PRECEDENCE);

    List<String> shadowed =
        perAgent.shadowedAdvisorNames(List.of(before, perAgent, generic, stranded));

    assertEquals(List.of("logging"), shadowed,
        "only a non-durable advisor ordered after the terminal durable advisor should be flagged");
  }

  private static CallAdvisor advisorAt(String name, int order) {
    return new CallAdvisor() {
      @Override
      public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(request);
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public int getOrder() {
        return order;
      }
    };
  }

  /** A ToolCallingChatOptions whose getToolCallbacks() is null — what a no-tools ChatClient yields. */
  static class NullToolsOptions implements ToolCallingChatOptions {
    @Override
    public List<ToolCallback> getToolCallbacks() {
      return null;
    }

    @Override
    public Map<String, Object> getToolContext() {
      return null;
    }

    @Override
    public String getModel() {
      return null;
    }

    @Override
    public Double getFrequencyPenalty() {
      return null;
    }

    @Override
    public Integer getMaxTokens() {
      return null;
    }

    @Override
    public Double getPresencePenalty() {
      return null;
    }

    @Override
    public List<String> getStopSequences() {
      return null;
    }

    @Override
    public Double getTemperature() {
      return null;
    }

    @Override
    public Integer getTopK() {
      return null;
    }

    @Override
    public Double getTopP() {
      return null;
    }

    @Override
    public ToolCallingChatOptions.Builder<?> mutate() {
      return null;
    }
  }
}
