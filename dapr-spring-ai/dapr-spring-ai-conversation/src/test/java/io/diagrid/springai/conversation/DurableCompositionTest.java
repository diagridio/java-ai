package io.diagrid.springai.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dapr.client.DaprPreviewClient;
import io.dapr.client.domain.ConversationRequestAlpha2;
import io.dapr.client.domain.ConversationResponseAlpha2;
import io.dapr.client.domain.ConversationResultAlpha2;
import io.dapr.client.domain.ConversationResultChoices;
import io.dapr.client.domain.ConversationResultMessage;
import io.dapr.client.domain.ConversationToolCalls;
import io.dapr.client.domain.ConversationToolCallsOfFunction;
import io.diagrid.springai.durable.workflow.DefinitionOnlyToolCallback;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

/**
 * Durable-path composition check: the durability layer attaches tools as
 * {@link DefinitionOnlyToolCallback}s (which throw if executed) to a mutation of the model's
 * default options, exactly as {@code DefaultChatOptionsFactory} does. This model must advertise
 * those definitions and only ever <em>return</em> tool calls — the workflow's ToolInvokeActivity
 * executes them. If the model ever invoked a callback, the backstop would throw and this test
 * would fail.
 */
class DurableCompositionTest {

  private final DaprPreviewClient client = mock(DaprPreviewClient.class);

  @Test
  void durableToolRoundTripReturnsToolCallsWithoutInvokingTheCallback() {
    when(client.converseAlpha2(any())).thenReturn(Mono.just(
        new ConversationResponseAlpha2(null, List.of(new ConversationResultAlpha2(
            List.of(new ConversationResultChoices("tool_calls", 0, new ConversationResultMessage(null,
                List.of(new ConversationToolCalls(
                    new ConversationToolCallsOfFunction("weather", "{\"city\":\"Malaga\"}"))
                    .setId("call-1"))))),
            null, null)))));
    DaprConversationChatModel model = new DaprConversationChatModel(client, "openai");

    // Mirror DefaultChatOptionsFactory: mutate the model's defaults and attach the durable
    // definition-only callbacks. The mutation must expose a tool-calling builder.
    ToolCallback durableTool = new DefinitionOnlyToolCallback(new ToolSpec(
        "weather", "Current weather", "{\"type\":\"object\"}"));
    ChatOptions.Builder<?> builder = model.getDefaultOptions().mutate();
    assertTrue(builder instanceof ToolCallingChatOptions.Builder<?>,
        "getDefaultOptions() must mutate into a ToolCallingChatOptions.Builder or the durable "
            + "options factory cannot attach tool callbacks");
    ((ToolCallingChatOptions.Builder<?>) builder).toolCallbacks(List.of(durableTool));

    ChatResponse response =
        model.call(new Prompt(List.<Message>of(new UserMessage("weather in Malaga?")), builder.build()));

    // The tool was advertised on the request...
    ArgumentCaptor<ConversationRequestAlpha2> captor =
        ArgumentCaptor.forClass(ConversationRequestAlpha2.class);
    verify(client).converseAlpha2(captor.capture());
    assertEquals("weather", captor.getValue().getTools().get(0).getFunction().getName());

    // ...and the model returned the tool call for the workflow to execute. Reaching this point
    // proves the callback was never invoked: DefinitionOnlyToolCallback.call throws.
    AssistantMessage.ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
    assertEquals("call-1", toolCall.id());
    assertEquals("weather", toolCall.name());
  }
}
