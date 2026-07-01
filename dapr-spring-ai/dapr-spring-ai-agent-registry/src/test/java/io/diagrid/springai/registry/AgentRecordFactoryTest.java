package io.diagrid.springai.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.springai.registry.model.AgentMetadata;
import io.diagrid.springai.registry.model.AgentMetadataSchema;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

class AgentRecordFactoryTest {

  static class WeatherTools {
    @Tool(description = "Get the current weather for a city")
    public String getWeather(String city) {
      return city + ": sunny";
    }
  }

  private final AgentRecordFactory factory =
      new AgentRecordFactory("travel-app", "OllamaChatModel", "ollama", "default-model");

  private ChatClientRequest request(SystemMessage system, ToolCallingChatOptions options) {
    Prompt prompt = new Prompt(List.of(system, new UserMessage("what is the weather?")), options);
    return new ChatClientRequest(prompt, new HashMap<>());
  }

  @Test
  void buildsCanonicalRecordFromTheLiveCall() {
    ToolCallingChatOptions options = ToolCallingChatOptions.builder()
        .model("llama3.2")
        .toolCallbacks(ToolCallbacks.from(new WeatherTools()))
        .build();
    AgentMetadataSchema schema =
        factory.build("weatherAssistant", request(new SystemMessage("You are a weather assistant."), options), false);

    assertEquals("0.1.0", schema.version());
    assertEquals("weatherAssistant", schema.name());
    assertEquals("travel-app", schema.agent().appId());
    assertEquals("Agent", schema.agent().type());
    assertEquals("spring-ai", schema.agent().framework());
    assertEquals("You are a weather assistant.", schema.agent().systemPrompt());

    assertEquals("ollama", schema.llm().provider());
    assertEquals("OllamaChatModel", schema.llm().client());
    assertEquals("llama3.2", schema.llm().model());

    List<String> toolNames = schema.tools().stream().map(t -> t.name()).toList();
    assertTrue(toolNames.contains("getWeather"), "tool must be captured: " + toolNames);
  }

  @Test
  void thinRecordHasAppAndModelButNoPromptOrTools() {
    AgentMetadataSchema schema = factory.buildThin("weatherAssistant", false);
    assertEquals("0.1.0", schema.version());
    assertEquals("weatherAssistant", schema.name());
    assertEquals("travel-app", schema.agent().appId());
    assertEquals("Agent", schema.agent().type());
    assertEquals("default-model", schema.llm().model());
    assertNull(schema.agent().systemPrompt(), "thin record has no system prompt until first call");
    assertNull(schema.tools(), "thin record has no tools until first call");
    assertNull(schema.agent().metadata(), "a standard agent carries no workflow_name");
  }

  @Test
  void durableAgentCarriesTypeAndWorkflowNameForCorrelation() {
    ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
    AgentMetadata built =
        factory.build("weatherAssistant", request(new SystemMessage("x"), options), true).agent();
    assertEquals("DurableAgent", built.type());
    assertEquals("spring-ai.weatherAssistant.workflow", built.metadata().get("workflow_name"));

    AgentMetadata thin = factory.buildThin("weatherAssistant", true).agent();
    assertEquals("DurableAgent", thin.type());
    assertEquals("spring-ai.weatherAssistant.workflow", thin.metadata().get("workflow_name"));
  }

  @Test
  void fallsBackToDefaultModelAndOmitsToolsWhenNoneSet() {
    ToolCallingChatOptions options = ToolCallingChatOptions.builder().build(); // no model, no tools
    AgentMetadataSchema schema =
        factory.build("itineraryFormatter", request(new SystemMessage("Format the itinerary."), options), false);

    assertEquals("default-model", schema.llm().model(), "model falls back to the ChatModel default");
    assertNull(schema.tools(), "no tools must be omitted, not an empty list");
  }
}
