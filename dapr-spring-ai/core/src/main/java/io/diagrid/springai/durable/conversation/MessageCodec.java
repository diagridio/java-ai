package io.diagrid.springai.durable.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Converts between Spring AI {@code Message} objects and the serializable {@link MessageRecord}
 * projection, and (de)serializes records as JSON.
 *
 * <p>Conversion to Spring AI types is confined to this class so that the rest of the workflow/
 * activity code deals only in framework-agnostic records. The JSON form uses Jackson 2 to match the
 * converter durabletask uses for activity payloads.
 */
public final class MessageCodec {

  private final ObjectMapper objectMapper;

  public MessageCodec() {
    this(new ObjectMapper());
  }

  public MessageCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Projects a Spring AI {@code Message} to a serializable {@link MessageRecord}. */
  public MessageRecord toRecord(Message message) {
    return switch (message.getMessageType()) {
      case SYSTEM -> MessageRecord.system(message.getText());
      case USER -> MessageRecord.user(message.getText());
      case ASSISTANT -> toAssistantRecord((AssistantMessage) message);
      case TOOL -> toToolRecord((ToolResponseMessage) message);
    };
  }

  /** Reconstructs a Spring AI {@code Message} from a {@link MessageRecord}. */
  public Message toMessage(MessageRecord record) {
    return switch (record.role()) {
      case SYSTEM -> new SystemMessage(record.text());
      case USER -> new UserMessage(record.text());
      case ASSISTANT -> toAssistantMessage(record);
      case TOOL -> toToolResponseMessage(record);
    };
  }

  public List<MessageRecord> toRecords(List<Message> messages) {
    return messages.stream().map(this::toRecord).toList();
  }

  public List<Message> toMessages(List<MessageRecord> records) {
    return records.stream().map(this::toMessage).toList();
  }

  public String writeAsString(MessageRecord record) {
    try {
      return objectMapper.writeValueAsString(record);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize MessageRecord", e);
    }
  }

  public MessageRecord readFromString(String json) {
    try {
      return objectMapper.readValue(json, MessageRecord.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize MessageRecord", e);
    }
  }

  private MessageRecord toAssistantRecord(AssistantMessage message) {
    List<ToolCallRecord> toolCalls =
        message.getToolCalls().stream()
            .map(tc -> new ToolCallRecord(tc.id(), tc.type(), tc.name(), tc.arguments()))
            .toList();
    return MessageRecord.assistant(message.getText(), toolCalls);
  }

  private MessageRecord toToolRecord(ToolResponseMessage message) {
    List<ToolResponseRecord> responses =
        message.getResponses().stream()
            .map(r -> new ToolResponseRecord(r.id(), r.name(), r.responseData()))
            .toList();
    return MessageRecord.tool(responses);
  }

  private Message toAssistantMessage(MessageRecord record) {
    List<AssistantMessage.ToolCall> toolCalls =
        record.toolCalls() == null
            ? List.of()
            : record.toolCalls().stream()
                .map(
                    tc ->
                        new AssistantMessage.ToolCall(
                            tc.id(), tc.type(), tc.name(), tc.arguments()))
                .toList();
    return AssistantMessage.builder()
        .content(record.text() == null ? "" : record.text())
        .toolCalls(toolCalls)
        .build();
  }

  private Message toToolResponseMessage(MessageRecord record) {
    List<ToolResponseMessage.ToolResponse> responses =
        record.toolResponses() == null
            ? List.of()
            : record.toolResponses().stream()
                .map(
                    r ->
                        new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData()))
                .toList();
    return ToolResponseMessage.builder().responses(responses).build();
  }
}
