package io.diagrid.dapr.springai.durable.workflow;

import io.diagrid.dapr.springai.durable.conversation.MessageRecord;
import java.util.List;
import java.util.Map;

/**
 * Input to the agent workflow: one {@code ChatClient} call rendered as serializable data.
 *
 * <p>Carries the seed conversation (system/user messages as captured after all upstream advisors
 * have run), the tool surface, the chat options, and an optional trace-propagation carrier. It is
 * <b>not</b> the durability handle: every call is scheduled under a fresh random workflow instance id
 * (dapr-agents parity), so this request is pure workflow input, not an identity key. Because ids are
 * random, adding fields here cannot perturb execution identity.
 *
 * @param messages     seed messages (system/user) for the conversation
 * @param toolSpecs    tools available to the model
 * @param options      portable chat options (model, temperature, max tokens, etc.) for the call
 * @param traceContext W3C trace-propagation carrier captured on the caller thread so activity spans
 *                     nest under the originating request's trace; {@code null}/empty ⇒ no propagation
 */
public record AgentRequest(
    List<MessageRecord> messages,
    List<ToolSpec> toolSpecs,
    ChatOptionsSpec options,
    Map<String, String> traceContext) {

  /** Convenience for a call with no trace propagation ({@code traceContext == null}). */
  public AgentRequest(List<MessageRecord> messages, List<ToolSpec> toolSpecs, ChatOptionsSpec options) {
    this(messages, toolSpecs, options, null);
  }
}
