package io.diagrid.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dapr.durabletask.PropagatedHistory;
import io.dapr.workflows.WorkflowActivityContext;
import io.diagrid.springai.durable.conversation.MessageRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * The activity must project the model's response metadata (usage, model, id) and finish reason into
 * the serializable {@link LlmResult}, so the workflow can aggregate it. Uses a stub {@code ChatModel}
 * returning a known {@code ChatResponse}.
 */
class LlmInvokeActivityTest {

  @Test
  void mapsUsageModelAndIdFromChatResponse() {
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder()
            .model("gpt-4o-2024")
            .id("resp-123")
            .usage(new DefaultUsage(11, 22, 33))
            .build();
    ChatResponse response =
        new ChatResponse(
            List.of(
                new Generation(
                    new AssistantMessage("hello"),
                    ChatGenerationMetadata.builder().finishReason("stop").build())),
            metadata);

    LlmInvokeActivity activity =
        new LlmInvokeActivity(
            chatModelReturning(response), (spec, callbacks) -> ToolCallingChatOptions.builder().build());
    LlmActivityInput input =
        new LlmActivityInput(List.of(MessageRecord.user("hi")), List.of(), ChatOptionsSpec.empty());

    LlmResult result = (LlmResult) activity.run(contextOf(input));

    assertEquals("hello", result.text());
    assertEquals("stop", result.finishReason());
    assertEquals(11, result.promptTokens().intValue());
    assertEquals(22, result.completionTokens().intValue());
    assertEquals(33, result.totalTokens().intValue());
    assertEquals("gpt-4o-2024", result.model());
    assertEquals("resp-123", result.responseId());
    assertTrue(result.toolCalls().isEmpty());
  }

  private static ChatModel chatModelReturning(ChatResponse response) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        return response;
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(response);
      }

      @Override
      public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
      }

      @Override
      public ChatOptions getOptions() {
        return getDefaultOptions();
      }
    };
  }

  private static WorkflowActivityContext contextOf(LlmActivityInput input) {
    return new WorkflowActivityContext() {
      @Override
      public Logger getLogger() {
        return null;
      }

      @Override
      public String getName() {
        return "dsa.llm.invoke";
      }

      @Override
      public String getTaskExecutionId() {
        return "t1";
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> T getInput(Class<T> type) {
        return (T) input;
      }

      @Override
      public String getTraceParent() {
        return null;
      }

      @Override
      public Optional<PropagatedHistory> getPropagatedHistory() {
        return Optional.empty();
      }
    };
  }
}
