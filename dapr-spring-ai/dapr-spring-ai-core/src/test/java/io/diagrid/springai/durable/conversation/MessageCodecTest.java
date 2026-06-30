package io.diagrid.springai.durable.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Highest-risk integration point per the prototype brief: polymorphic {@code Message} round-tripping
 * through the flat {@link MessageRecord} projection and through JSON. Every Spring AI message subtype
 * the workflow can encounter must survive both round-trips losslessly.
 */
class MessageCodecTest {

  private final MessageCodec codec = new MessageCodec();

  @Test
  void systemMessageRoundTrips() {
    Message original = new SystemMessage("You are a helpful assistant.");

    MessageRecord record = codec.toRecord(original);
    assertEquals(Role.SYSTEM, record.role());
    assertEquals("You are a helpful assistant.", record.text());

    Message back = codec.toMessage(record);
    assertInstanceOf(SystemMessage.class, back);
    assertEquals(MessageType.SYSTEM, back.getMessageType());
    assertEquals(original.getText(), back.getText());
  }

  @Test
  void userMessageRoundTrips() {
    Message original = new UserMessage("book me a flight to Madrid");

    MessageRecord record = codec.toRecord(original);
    assertEquals(Role.USER, record.role());

    Message back = codec.toMessage(record);
    assertInstanceOf(UserMessage.class, back);
    assertEquals(MessageType.USER, back.getMessageType());
    assertEquals(original.getText(), back.getText());
  }

  @Test
  void assistantMessageWithoutToolCallsRoundTrips() {
    Message original = new AssistantMessage("Your flight is booked.");

    MessageRecord record = codec.toRecord(original);
    assertEquals(Role.ASSISTANT, record.role());
    assertEquals("Your flight is booked.", record.text());
    assertTrue(record.toolCalls() == null || record.toolCalls().isEmpty());

    Message back = codec.toMessage(record);
    assertInstanceOf(AssistantMessage.class, back);
    assertEquals(original.getText(), back.getText());
    assertTrue(((AssistantMessage) back).getToolCalls().isEmpty());
  }

  @Test
  void assistantMessageWithToolCallsRoundTrips() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("call_1", "function", "bookFlight", "{\"city\":\"Madrid\"}");
    Message original = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();

    MessageRecord record = codec.toRecord(original);
    assertEquals(Role.ASSISTANT, record.role());
    assertEquals(1, record.toolCalls().size());
    ToolCallRecord tc = record.toolCalls().get(0);
    assertEquals("call_1", tc.id());
    assertEquals("function", tc.type());
    assertEquals("bookFlight", tc.name());
    assertEquals("{\"city\":\"Madrid\"}", tc.arguments());

    Message back = codec.toMessage(record);
    AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, back);
    assertTrue(assistant.hasToolCalls());
    assertEquals(call, assistant.getToolCalls().get(0));
  }

  @Test
  void toolResponseMessageRoundTrips() {
    ToolResponseMessage.ToolResponse response =
        new ToolResponseMessage.ToolResponse("call_1", "bookFlight", "{\"confirmation\":\"AB123\"}");
    Message original = ToolResponseMessage.builder().responses(List.of(response)).build();

    MessageRecord record = codec.toRecord(original);
    assertEquals(Role.TOOL, record.role());
    assertEquals(1, record.toolResponses().size());
    ToolResponseRecord tr = record.toolResponses().get(0);
    assertEquals("call_1", tr.id());
    assertEquals("bookFlight", tr.name());
    assertEquals("{\"confirmation\":\"AB123\"}", tr.responseData());

    Message back = codec.toMessage(record);
    ToolResponseMessage toolMsg = assertInstanceOf(ToolResponseMessage.class, back);
    assertEquals(MessageType.TOOL, toolMsg.getMessageType());
    assertEquals(response, toolMsg.getResponses().get(0));
  }

  @Test
  void assistantWithToolCallsSurvivesJsonRoundTrip() {
    MessageRecord record =
        MessageRecord.assistant(
            "",
            List.of(new ToolCallRecord("call_9", "function", "lookup", "{\"q\":\"x\"}")));

    String json = codec.writeAsString(record);
    MessageRecord back = codec.readFromString(json);

    assertEquals(record, back);
  }

  @Test
  void toolResponseSurvivesJsonRoundTrip() {
    MessageRecord record =
        MessageRecord.tool(List.of(new ToolResponseRecord("call_9", "lookup", "result-data")));

    String json = codec.writeAsString(record);
    MessageRecord back = codec.readFromString(json);

    assertEquals(record, back);
  }
}
