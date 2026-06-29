package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import java.util.List;
import java.util.Map;

/**
 * Input to the agent workflow: one {@code ChatClient} call rendered as serializable data.
 *
 * <p>Carries the seed conversation (system/user messages as captured after all upstream advisors
 * have run), the tool surface, the chat options, and an optional conversation id.
 *
 * <p>The durability handle is derived from this request. When a {@code conversationId} is present it
 * is the dedup key (combined with the turn position); otherwise the derivation falls back to hashing
 * the content. See {@code InstanceIdDerivation}.
 *
 * @param messages       seed messages (system/user) for the conversation
 * @param toolSpecs      tools available to the model
 * @param options        chat options (model/component name, temperature, etc.), as a canonical map
 * @param conversationId conversation id (e.g. Spring AI's {@code ChatMemory.CONVERSATION_ID}), or
 *                       {@code null} for a stateless call with no conversation
 */
public record AgentRequest(
    List<MessageRecord> messages,
    List<ToolSpec> toolSpecs,
    Map<String, Object> options,
    String conversationId) {

  /** Convenience for a stateless call with no conversation id (content-hash durability key). */
  public AgentRequest(List<MessageRecord> messages, List<ToolSpec> toolSpecs, Map<String, Object> options) {
    this(messages, toolSpecs, options, null);
  }
}
