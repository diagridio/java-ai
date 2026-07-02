package io.diagrid.springai.memory;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.State;
import io.dapr.client.domain.StateOptions;
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
 *
 * <p><b>Concurrency contract: one writer per conversation id at a time.</b> Spring AI's
 * {@code MessageWindowChatMemory} does read-modify-write across two calls
 * ({@link #findByConversationId} → merge/window → {@link #saveAll}), so two concurrent turns on the
 * same conversation id would both read the same base and both write the full window — last-write-wins
 * would silently erase a turn. This repository instead uses <b>optimistic concurrency (detect and
 * fail)</b>: {@code saveAll} re-reads the current etag and writes conditionally on it; a concurrent
 * writer that wrote in between changed the etag, so the second write is rejected with a
 * {@link ConcurrentConversationModificationException}.
 *
 * <p>It deliberately does <b>not</b> merge conflicting windows. Each assistant reply was generated
 * without seeing the other turn, so any interleaving fabricates a causally false history; a conflict
 * therefore means concurrent writers on one conversation id, which is an application-level error
 * (double-submit, missing sticky routing, two agents sharing an id) and is surfaced loudly. This
 * fail-loud posture is intentional and differs from the registry module's log-and-swallow posture:
 * chat memory is <i>user data</i>, whereas the registry is best-effort metadata.
 *
 * <p>Failing the save is cheap by design: the LLM work is protected by the durable layer, so a
 * reissued turn derives the same content-hash instance id, reattaches to the completed workflow
 * without re-running anything, and re-attempts the save against the now-current etag.
 *
 * <p><b>First-turn caveat.</b> The etag guards the update path fully. The very first write for a
 * conversation has no etag yet; it is sent with {@link StateOptions.Concurrency#FIRST_WRITE}, but the
 * pinned Dapr SDK omits the etag entirely when none is present, so whether the runtime/state-store
 * enforces strict create-only for a first-write-without-etag is component-dependent and is not
 * verified here. Two <i>simultaneous first turns</i> on a brand-new conversation id may therefore
 * still race; every subsequent turn is guarded.
 */
public final class DaprStateChatMemoryRepository implements ChatMemoryRepository {

  private static final Set<MessageType> PERSISTED =
      Set.of(MessageType.USER, MessageType.ASSISTANT, MessageType.SYSTEM);

  // Optimistic concurrency: FIRST_WRITE makes the write conditional on the etag we read (and best-
  // effort guards the create). Consistency is left at the store default (null → not sent).
  private static final StateOptions OPTIMISTIC =
      new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE);

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
    String key = memoryKey(agentName, conversationId);

    // Re-acquire the current etag here: the ChatMemoryRepository seam gives no way to carry it from
    // the caller's earlier read. Write conditionally on it (etag is null on the first turn), so a
    // concurrent writer that changed the etag in between causes this write to be rejected.
    State<StoredConversation> current =
        client.getState(statestore, key, StoredConversation.class).block();
    String etag = current == null ? null : current.getEtag();

    try {
      client.saveState(statestore, key, etag, new StoredConversation(stored), OPTIMISTIC).block();
    } catch (RuntimeException e) {
      if (isEtagMismatch(e)) {
        throw new ConcurrentConversationModificationException(conversationId, e);
      }
      throw e;
    }
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    client.deleteState(statestore, memoryKey(agentName, conversationId)).block();
  }

  // A rejected conditional write surfaces as an "etag mismatch" error; detect it by message (mirrors
  // DurableRunner.isAlreadyExists) so only a genuine conflict is translated — other failures (sidecar
  // down, serialization, etc.) propagate unchanged.
  private static boolean isEtagMismatch(RuntimeException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("etag")) {
        return true;
      }
    }
    return false;
  }
}
