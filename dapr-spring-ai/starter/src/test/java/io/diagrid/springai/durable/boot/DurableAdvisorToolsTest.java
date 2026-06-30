package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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

  /** Captures the AgentRequest the advisor would hand to the workflow, without touching Dapr. */
  static class CapturingRunner extends DurableRunner {
    AgentRequest captured;

    CapturingRunner() {
      super(null, new InstanceIdDerivation(), Duration.ofSeconds(1));
    }

    @Override
    public String run(AgentRequest request) {
      this.captured = request;
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
}
