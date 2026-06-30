package io.diagrid.springai.durable.conversation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * Reconstructs the running conversation as a pure function of the workflow's seed plus its ordered
 * accumulated turn records.
 *
 * <p>Per the prototype design, the orchestrator holds no separate {@code List<Message>} as durable
 * state. On every (re)execution it rebuilds the conversation from the seed messages (system/user,
 * carried in the workflow input) and the records derived from each completed activity result, in
 * the order they occurred. Dapr already persists those activity results; this avoids double
 * bookkeeping and keeps workflow payloads small.
 *
 * <p>Pure and side-effect free: identical inputs always yield an identical message list.
 */
public final class ConversationDecoder {

  private final MessageCodec codec;

  public ConversationDecoder(MessageCodec codec) {
    this.codec = codec;
  }

  /**
   * Rebuilds the full conversation to feed the next LLM call.
   *
   * @param seed  initial system/user message records from the workflow input
   * @param turns assistant and tool-batch records accumulated from activity results, in order
   * @return the ordered Spring AI messages: seed, then each turn
   */
  public List<Message> decode(List<MessageRecord> seed, List<MessageRecord> turns) {
    List<MessageRecord> all = new ArrayList<>(seed.size() + turns.size());
    all.addAll(seed);
    all.addAll(turns);
    return codec.toMessages(all);
  }

  /**
   * Decodes an already-accumulated, ordered list of conversation records into Spring AI messages.
   * Used inside the LLM activity, where the orchestrator has already concatenated seed and turns.
   */
  public List<Message> decode(List<MessageRecord> conversation) {
    return codec.toMessages(conversation);
  }
}
