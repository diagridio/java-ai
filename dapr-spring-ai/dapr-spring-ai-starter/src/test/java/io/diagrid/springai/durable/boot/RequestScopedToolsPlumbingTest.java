package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Pins the two Spring AI facts the fix depends on: a tool attached per-ChatClient via
 * {@code .defaultTools(...)} surfaces on the request prompt options as
 * {@link ToolCallingChatOptions#getToolCallbacks()}, and an advisor at {@code LOWEST_PRECEDENCE - 1}
 * reliably runs (the terminal model-call advisor sits at {@code LOWEST_PRECEDENCE}).
 */
class RequestScopedToolsPlumbingTest {

  static class WeatherTools {
    @Tool(description = "Get the current weather for a city")
    public String getWeather(String city) {
      return "sunny, 22C";
    }
  }

  @Test
  void defaultToolsSurfaceAsRequestToolCallbacks() {
    AtomicReference<ChatClientRequest> captured = new AtomicReference<>();

    CallAdvisor capturing =
        new CallAdvisor() {
          @Override
          public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            captured.set(request);
            return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))),
                new HashMap<>(request.context()));
          }

          @Override
          public String getName() {
            return "capture";
          }

          @Override
          public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE - 1;
          }
        };

    // Real providers' default options are ToolCallingChatOptions (e.g. OllamaChatOptions); that is
    // what lets Spring AI merge .defaultTools(...) into the prompt options.
    ChatOptions toolCapableDefaults = ToolCallingChatOptions.builder().build();
    ChatModel stub =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("MODEL"))));
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
          }

          @Override
          public ChatOptions getDefaultOptions() {
            return toolCapableDefaults;
          }

          @Override
          public ChatOptions getOptions() {
            return toolCapableDefaults;
          }
        };

    ChatClient client =
        ChatClient.builder(stub).defaultTools(new WeatherTools()).defaultAdvisors(capturing).build();
    client.prompt().user("weather in Paris?").call().content();

    ChatClientRequest request = captured.get();
    assertNotNull(request, "advisor at LOWEST_PRECEDENCE-1 did not run");
    ChatOptions options = request.prompt().getOptions();
    assertInstanceOf(ToolCallingChatOptions.class, options);
    List<ToolCallback> callbacks = ((ToolCallingChatOptions) options).getToolCallbacks();
    assertTrue(
        callbacks.stream().anyMatch(c -> c.getToolDefinition().name().equals("getWeather")),
        "expected the request-scoped getWeather tool among the prompt options' tool callbacks");
  }
}
