package io.diagrid.springai.memory;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.State;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * A Spring AI {@link ChatMemoryRepository} that persists conversation history in a Dapr state store,
 * keyed by conversation id, so history survives restarts and is shared across replicas.
 *
 * <p>Only conversational turns (user/assistant/system) are persisted; tool messages are omitted, as
 * they are not part of the durable conversation. {@link #findConversationIds()} is unsupported — a
 * Dapr state store can't enumerate keys — and returns an empty list; it is not used by the normal
 * add/get/clear flow.
 */
public final class DaprStateChatMemoryRepository implements ChatMemoryRepository {

  private static final Set<MessageType> PERSISTED =
      Set.of(MessageType.USER, MessageType.ASSISTANT, MessageType.SYSTEM);

  private final DaprClient client;
  private final String statestore;
  private final String agentName;

  /**
   * @param client     Dapr client for state operations
   * @param statestore state store component name that holds conversation history
   * @param agentName  namespace for the keys (see {@link #memoryKey(String, String)})
   */
  public DaprStateChatMemoryRepository(DaprClient client, String statestore, String agentName) {
    this.client = client;
    this.statestore = statestore;
    this.agentName = agentName;
  }

  /**
   * The state-store key for a conversation, in the Dapr Agents format
   * {@code {agentName}:_memory_{conversationId}} with spaces in the agent name replaced by dashes and
   * the whole key lowercased — so records interoperate with Dapr Agents' memory layout.
   *
   * @param agentName      key namespace
   * @param conversationId the conversation id
   * @return the state-store key
   */
  static String memoryKey(String agentName, String conversationId) {
    return (agentName.replace(' ', '-') + ":_memory_" + conversationId).toLowerCase(Locale.ROOT);
  }

  @Override
  public List<String> findConversationIds() {
    return List.of();
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    State<StoredConversation> state =
        client.getState(statestore, memoryKey(agentName, conversationId), StoredConversation.class).block();
    StoredConversation conversation = state == null ? null : state.getValue();
    if (conversation == null || conversation.messages() == null) {
      return List.of();
    }
    return conversation.messages().stream().map(StoredMessage::toMessage).toList();
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    List<StoredMessage> stored = messages.stream()
        .filter(message -> PERSISTED.contains(message.getMessageType()))
        .map(StoredMessage::of)
        .toList();
    client.saveState(statestore, memoryKey(agentName, conversationId), new StoredConversation(stored)).block();
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    client.deleteState(statestore, memoryKey(agentName, conversationId)).block();
  }
}
