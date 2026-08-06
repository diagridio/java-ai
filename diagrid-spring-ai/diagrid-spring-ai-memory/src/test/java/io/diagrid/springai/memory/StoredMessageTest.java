package io.diagrid.springai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

class StoredMessageTest {

  @Test
  void roundTripsUserMessage() {
    assertRoundTrip(new UserMessage("what is the weather?"), UserMessage.class, "what is the weather?");
  }

  @Test
  void roundTripsSystemMessage() {
    assertRoundTrip(new SystemMessage("You are a weather assistant."), SystemMessage.class, "You are a weather assistant.");
  }

  @Test
  void roundTripsAssistantMessage() {
    assertRoundTrip(AssistantMessage.builder().content("It is sunny.").build(), AssistantMessage.class, "It is sunny.");
  }

  @Test
  void buildsKeyInDaprAgentsFormat() {
    assertEquals(
        "default:_memory_trip-42", DaprStateChatMemoryRepository.memoryKey("default", "trip-42"));
    // agent-name spaces become dashes and the whole key is lowercased, matching Dapr Agents.
    assertEquals(
        "weather-agent:_memory_abc", DaprStateChatMemoryRepository.memoryKey("Weather Agent", "ABC"));
  }

  private void assertRoundTrip(Message original, Class<?> expectedType, String expectedText) {
    StoredMessage stored = StoredMessage.of(original);
    assertEquals(original.getMessageType().name(), stored.type());
    assertEquals(expectedText, stored.text());

    Message restored = stored.toMessage();
    assertInstanceOf(expectedType, restored);
    assertEquals(expectedText, restored.getText());
  }
}
