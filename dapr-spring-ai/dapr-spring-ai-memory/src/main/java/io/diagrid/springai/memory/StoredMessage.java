package io.diagrid.springai.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * A conversation message projected to serializable {@code {type, text}} for the Dapr state store.
 *
 * <p>Only conversational turns are persisted (user/assistant/system); tool messages are not — they
 * are filtered out before storage, so {@link #toMessage()} never sees {@code TOOL}.
 *
 * @param type the message type name ({@code USER}, {@code ASSISTANT} or {@code SYSTEM})
 * @param text the message text
 */
public record StoredMessage(@JsonProperty("type") String type, @JsonProperty("text") String text) {

  static StoredMessage of(Message message) {
    return new StoredMessage(message.getMessageType().name(), message.getText());
  }

  Message toMessage() {
    String content = text == null ? "" : text;
    return switch (MessageType.valueOf(type)) {
      case USER -> new UserMessage(content);
      case SYSTEM -> new SystemMessage(content);
      case ASSISTANT -> AssistantMessage.builder().content(content).build();
      case TOOL -> throw new IllegalStateException("TOOL messages are not persisted to chat memory");
    };
  }
}
