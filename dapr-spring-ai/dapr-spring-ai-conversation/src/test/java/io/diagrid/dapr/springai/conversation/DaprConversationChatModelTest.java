package io.diagrid.dapr.springai.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.dapr.client.DaprPreviewClient;
import io.dapr.client.domain.ConversationMessage;
import io.dapr.client.domain.ConversationMessageRole;
import io.dapr.client.domain.ConversationRequestAlpha2;
import io.dapr.client.domain.ConversationResponseAlpha2;
import io.dapr.client.domain.ConversationResultAlpha2;
import io.dapr.client.domain.ConversationResultChoices;
import io.dapr.client.domain.ConversationResultCompletionUsage;
import io.dapr.client.domain.ConversationResultMessage;
import io.dapr.client.domain.ConversationToolCalls;
import io.dapr.client.domain.ConversationToolCallsOfFunction;
import io.dapr.client.domain.ToolMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;

class DaprConversationChatModelTest {

  private final DaprPreviewClient client = mock(DaprPreviewClient.class);

  private DaprConversationChatModel model() {
    return new DaprConversationChatModel(client, "echo");
  }

  private ConversationRequestAlpha2 callAndCaptureRequest(Prompt prompt, DaprConversationChatModel model) {
    stubTextResponse("ok");
    model.call(prompt);
    ArgumentCaptor<ConversationRequestAlpha2> captor =
        ArgumentCaptor.forClass(ConversationRequestAlpha2.class);
    org.mockito.Mockito.verify(client).converseAlpha2(captor.capture());
    return captor.getValue();
  }

  private void stubTextResponse(String text) {
    stubResponse(new ConversationResponseAlpha2(null, List.of(new ConversationResultAlpha2(
        List.of(new ConversationResultChoices("stop", 0, new ConversationResultMessage(text))),
        null, null))));
  }

  private void stubResponse(ConversationResponseAlpha2 response) {
    when(client.converseAlpha2(any())).thenReturn(Mono.just(response));
  }

  @Test
  void mapsEveryRoleOntoItsConversationMessage() {
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage("be brief"),
        new UserMessage("hi"),
        AssistantMessage.builder().content("calling a tool").toolCalls(List.of(
            new AssistantMessage.ToolCall("call-1", "function", "weather", "{\"city\":\"Malaga\"}"))).build(),
        ToolResponseMessage.builder().responses(List.of(
            new ToolResponseMessage.ToolResponse("call-1", "weather", "sunny"),
            new ToolResponseMessage.ToolResponse("call-2", "traffic", "clear"))).build()));

    List<ConversationMessage> messages =
        callAndCaptureRequest(prompt, model()).getInputs().get(0).getMessages();

    // The tool-response message fans out to one TOOL message per response: 3 messages in → 5 out.
    assertEquals(5, messages.size());
    assertEquals(ConversationMessageRole.SYSTEM, messages.get(0).getRole());
    assertEquals("be brief", messages.get(0).getContent().get(0).getText());
    assertEquals(ConversationMessageRole.USER, messages.get(1).getRole());
    assertEquals("hi", messages.get(1).getContent().get(0).getText());

    io.dapr.client.domain.AssistantMessage assistant =
        (io.dapr.client.domain.AssistantMessage) messages.get(2);
    assertEquals("calling a tool", assistant.getContent().get(0).getText());
    ConversationToolCalls toolCall = assistant.getToolCalls().get(0);
    assertEquals("call-1", toolCall.getId());
    assertEquals("weather", toolCall.getFunction().getName());
    assertEquals("{\"city\":\"Malaga\"}", toolCall.getFunction().getArguments());

    ToolMessage firstTool = (ToolMessage) messages.get(3);
    assertEquals("call-1", firstTool.getToolId());
    assertEquals("weather", firstTool.getName());
    assertEquals("sunny", firstTool.getContent().get(0).getText());
    ToolMessage secondTool = (ToolMessage) messages.get(4);
    assertEquals("call-2", secondTool.getToolId());
  }

  @Test
  void mapsReturnedToolCallsOntoTheAssistantMessageWithoutExecutingAnything() {
    stubResponse(new ConversationResponseAlpha2(null, List.of(new ConversationResultAlpha2(
        List.of(new ConversationResultChoices("tool_calls", 0, new ConversationResultMessage(null,
            List.of(new ConversationToolCalls(
                new ConversationToolCallsOfFunction("weather", "{\"city\":\"Malaga\"}"))
                .setId("call-9"))))),
        null, null))));

    ChatResponse response = model().call(new Prompt("what's the weather?"));

    AssistantMessage assistant = response.getResult().getOutput();
    assertTrue(assistant.hasToolCalls());
    AssistantMessage.ToolCall toolCall = assistant.getToolCalls().get(0);
    assertEquals("call-9", toolCall.id());
    assertEquals("function", toolCall.type());
    assertEquals("weather", toolCall.name());
    assertEquals("{\"city\":\"Malaga\"}", toolCall.arguments());
    assertEquals("tool_calls", response.getResult().getMetadata().getFinishReason());
  }

  @Test
  void advertisesToolDefinitionsFromToolCallingChatOptions() {
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
        .name("weather")
        .description("Current weather for a city")
        .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
        .build());
    Prompt prompt = new Prompt(List.<Message>of(new UserMessage("hi")),
        ToolCallingChatOptions.builder().toolCallbacks(callback).build());

    ConversationRequestAlpha2 request = callAndCaptureRequest(prompt, model());

    assertEquals(1, request.getTools().size());
    var function = request.getTools().get(0).getFunction();
    assertEquals("weather", function.getName());
    assertEquals("Current weather for a city", function.getDescription());
    assertEquals("object", function.getParameters().get("type"));
    assertEquals(Map.of("city", Map.of("type", "string")), function.getParameters().get("properties"));
  }

  @Test
  void invalidToolSchemaFailsFastNamingTheTool() {
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
        .name("weather").description("d").inputSchema("not json").build());
    stubTextResponse("ok");
    Prompt prompt = new Prompt(List.<Message>of(new UserMessage("hi")),
        ToolCallingChatOptions.builder().toolCallbacks(callback).build());

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> model().call(prompt));
    assertTrue(e.getMessage().contains("weather"));
  }

  @Test
  void userMediaFailsFastInsteadOfBeingDropped() {
    UserMessage withImage = UserMessage.builder().text("what is this?")
        .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1}))).build();

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> model().call(new Prompt(withImage)));
    assertTrue(e.getMessage().contains("text-only"));
  }

  @Test
  void runtimeTemperatureOverridesTheConfiguredDefault() {
    DaprConversationChatModel model =
        new DaprConversationChatModel(client, "echo", null, false, 0.2);

    Prompt withRuntime = new Prompt(List.<Message>of(new UserMessage("hi")),
        ChatOptions.builder().temperature(0.7).build());
    assertEquals(0.7, callAndCaptureRequest(withRuntime, model).getTemperature());
  }

  @Test
  void configuredDefaultsReachTheRequest() {
    DaprConversationChatModel model =
        new DaprConversationChatModel(client, "openai", "ctx-42", true, 0.2);

    ConversationRequestAlpha2 request = callAndCaptureRequest(new Prompt("hi"), model);

    assertEquals("openai", request.getName());
    assertEquals("ctx-42", request.getContextId());
    // Both scrub-pii flags: the request-level one covers LLM output, the input-level one covers
    // the prompt content sent to the provider.
    assertTrue(request.isScrubPii());
    assertTrue(request.getInputs().get(0).isScrubPii());
    assertEquals(0.2, request.getTemperature());
  }

  @Test
  void mapsModelUsageAndContextIdOntoResponseMetadata() {
    ConversationResultCompletionUsage usage = new ConversationResultCompletionUsage(5, 7, 12);
    stubResponse(new ConversationResponseAlpha2("ctx-42", List.of(new ConversationResultAlpha2(
        List.of(new ConversationResultChoices("stop", 0, new ConversationResultMessage("hello"))),
        "gpt-4o-mini", usage))));

    ChatResponse response = model().call(new Prompt("hi"));

    assertEquals("hello", response.getResult().getOutput().getText());
    assertEquals("gpt-4o-mini", response.getMetadata().getModel());
    assertEquals(7, response.getMetadata().getUsage().getPromptTokens());
    assertEquals(5, response.getMetadata().getUsage().getCompletionTokens());
    assertEquals(12, response.getMetadata().getUsage().getTotalTokens());
    assertEquals("ctx-42",
        response.getMetadata().get(DaprConversationChatModel.CONTEXT_ID_METADATA_KEY));
  }

  @Test
  void absentMetadataStaysAbsentInsteadOfBeingFabricated() {
    stubTextResponse("hello");

    ChatResponse response = model().call(new Prompt("hi"));

    // ChatResponseMetadata reports "" for an absent model and an empty usage, not invented values.
    assertEquals("", response.getMetadata().getModel());
    assertEquals(0, response.getMetadata().getUsage().getTotalTokens());
    assertNull(response.getMetadata().get(DaprConversationChatModel.CONTEXT_ID_METADATA_KEY));
  }

  @Test
  void missingComponentFailsAtConstruction() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new DaprConversationChatModel(client, " "));
    assertTrue(e.getMessage().contains("component"));
  }

  @Test
  void emptyChoicesFailFast() {
    stubResponse(new ConversationResponseAlpha2(null, List.of()));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> model().call(new Prompt("hi")));
    assertTrue(e.getMessage().contains("no choices"));
  }
}
