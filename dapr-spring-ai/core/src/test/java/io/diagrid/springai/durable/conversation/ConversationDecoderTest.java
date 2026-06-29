package io.diagrid.springai.durable.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.diagrid.springai.durable.workflow.LlmResult;
import io.diagrid.springai.durable.workflow.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Validates the decode-from-history pattern: a transcript of seed messages plus ordered activity
 * results must reconstruct the exact Spring AI message sequence, with tool results grouped into a
 * single {@code ToolResponseMessage} following the assistant turn that requested them.
 */
class ConversationDecoderTest {

  private final ConversationDecoder decoder = new ConversationDecoder(new MessageCodec());

  private List<MessageRecord> seed() {
    return List.of(
        MessageRecord.system("You are a travel agent."),
        MessageRecord.user("book me a flight to Madrid"));
  }

  @Test
  void seedOnlyDecodesToSystemAndUser() {
    List<Message> messages = decoder.decode(seed(), List.of());

    assertEquals(2, messages.size());
    assertInstanceOf(SystemMessage.class, messages.get(0));
    assertInstanceOf(UserMessage.class, messages.get(1));
  }

  @Test
  void fullToolRoundTripDecodesInOrder() {
    // Turn 1: assistant requests one tool call.
    LlmResult turn1 =
        new LlmResult(
            "",
            List.of(new ToolCallRecord("call_1", "function", "bookFlight", "{\"city\":\"Madrid\"}")),
            "tool_calls");

    // Tool batch answering turn 1.
    List<ToolResult> batch =
        List.of(new ToolResult("call_1", "bookFlight", "{\"confirmation\":\"AB123\"}"));

    // Turn 2: assistant produces the final answer, no tool calls.
    LlmResult turn2 = new LlmResult("Your flight to Madrid is booked (AB123).", List.of(), "stop");

    List<MessageRecord> turns =
        List.of(turn1.toMessageRecord(), ToolResult.toMessageRecord(batch), turn2.toMessageRecord());

    List<Message> messages = decoder.decode(seed(), turns);

    // system, user, assistant(tool calls), tool(responses), assistant(final)
    assertEquals(5, messages.size());
    assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
    assertEquals(MessageType.USER, messages.get(1).getMessageType());

    AssistantMessage assistant1 = assertInstanceOf(AssistantMessage.class, messages.get(2));
    assertEquals(1, assistant1.getToolCalls().size());
    assertEquals("bookFlight", assistant1.getToolCalls().get(0).name());

    ToolResponseMessage toolMsg = assertInstanceOf(ToolResponseMessage.class, messages.get(3));
    assertEquals(1, toolMsg.getResponses().size());
    assertEquals("call_1", toolMsg.getResponses().get(0).id());

    AssistantMessage assistant2 = assertInstanceOf(AssistantMessage.class, messages.get(4));
    assertEquals("Your flight to Madrid is booked (AB123).", assistant2.getText());
    assertEquals(0, assistant2.getToolCalls().size());
  }

  @Test
  void multipleToolCallsInOneTurnGroupIntoSingleToolMessage() {
    LlmResult turn1 =
        new LlmResult(
            "",
            List.of(
                new ToolCallRecord("call_1", "function", "checkWeather", "{\"city\":\"Madrid\"}"),
                new ToolCallRecord("call_2", "function", "checkHotels", "{\"city\":\"Madrid\"}")),
            "tool_calls");
    List<ToolResult> batch =
        List.of(
            new ToolResult("call_1", "checkWeather", "sunny"),
            new ToolResult("call_2", "checkHotels", "3 available"));

    List<MessageRecord> turns = List.of(turn1.toMessageRecord(), ToolResult.toMessageRecord(batch));
    List<Message> messages = decoder.decode(seed(), turns);

    assertEquals(4, messages.size());
    AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, messages.get(2));
    assertEquals(2, assistant.getToolCalls().size());

    // Both tool results collapse into ONE ToolResponseMessage following the assistant turn.
    ToolResponseMessage toolMsg = assertInstanceOf(ToolResponseMessage.class, messages.get(3));
    assertEquals(2, toolMsg.getResponses().size());
    assertEquals("call_1", toolMsg.getResponses().get(0).id());
    assertEquals("call_2", toolMsg.getResponses().get(1).id());
  }
}
