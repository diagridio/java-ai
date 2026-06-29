package io.diagrid.springai.durable.probe;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Probe (not a CI test): settles empirically whether Spring AI 2.0's OllamaChatModel auto-executes
 * tool calls during {@code call(Prompt)} or returns them to the caller. The durable design requires
 * the latter. The attached tool's function flips a flag and returns if ever invoked; if the call
 * returns an AssistantMessage carrying tool calls without that flag set, tools are NOT auto-executed.
 *
 * <p>Run manually against a local Ollama with: {@code mvn -pl core test -Dtest=ToolExecutionProbe}.
 */
@Tag("probe")
class ToolExecutionProbe {

  public record WeatherInput(String city) {}

  @Test
  void doesOllamaChatModelAutoExecuteTools() {
    AtomicBoolean toolInvoked = new AtomicBoolean(false);

    Function<WeatherInput, String> weatherFn =
        input -> {
          toolInvoked.set(true);
          return "sunny, 25C";
        };

    FunctionToolCallback<WeatherInput, String> weatherTool =
        FunctionToolCallback.builder("getWeather", weatherFn)
            .description("Get the current weather for a city")
            .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}")
            .inputType(WeatherInput.class)
            .build();

    OllamaChatModel model =
        OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
            .build();

    OllamaChatOptions options =
        OllamaChatOptions.builder()
            .model("llama3.1:8b")
            .temperature(0.0)
            .toolCallbacks(List.of(weatherTool))
            .build();

    Prompt prompt = new Prompt(List.of(new UserMessage("What is the weather in Madrid?")), options);

    ChatResponse response = model.call(prompt);
    AssistantMessage assistant = response.getResult().getOutput();

    System.out.println("=== PROBE RESULT ===");
    System.out.println("toolInvoked (auto-executed by ChatModel) = " + toolInvoked.get());
    System.out.println("assistant.hasToolCalls() = " + assistant.hasToolCalls());
    System.out.println("assistant.getToolCalls() = " + assistant.getToolCalls());
    System.out.println("assistant.getText() = " + assistant.getText());
    System.out.println("finishReason = " + response.getResult().getMetadata().getFinishReason());
    System.out.println("====================");
  }
}
