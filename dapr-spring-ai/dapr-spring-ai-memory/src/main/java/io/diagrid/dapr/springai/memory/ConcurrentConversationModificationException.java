package io.diagrid.dapr.springai.memory;

/**
 * Thrown when a chat-memory write is rejected because another writer modified the same conversation
 * concurrently (an etag mismatch). This is a <b>usage error</b>, not data corruption: a conversation
 * id must have a single writer at a time (sticky routing, no double-submit, not two agents sharing an
 * id). The write is failed loudly rather than merged, because two windows generated without seeing
 * each other cannot be reconciled without fabricating a false history — see
 * {@link DaprStateChatMemoryRepository}. The rejected turn's LLM work is preserved by the durable
 * layer and re-attempted on reissue, so failing here is cheap.
 */
public class ConcurrentConversationModificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param conversationId the conversation id with concurrent writers
   * @param cause          the underlying etag-mismatch failure from the state store
   */
  public ConcurrentConversationModificationException(String conversationId, Throwable cause) {
    super(
        "Concurrent writers on conversation id '" + conversationId + "': the memory write was "
            + "rejected (etag mismatch) to avoid silently losing a turn. Ensure a single writer per "
            + "conversation id (sticky routing / no double-submit).",
        cause);
  }
}
