package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import java.util.List;

/**
 * Input to the agent workflow: one {@code ChatClient} call rendered as serializable data.
 *
 * <p>Carries the seed conversation (system/user messages as captured after all upstream advisors
 * have run), the tool surface, and the chat options. It is <b>not</b> the durability handle: every
 * call is scheduled under a fresh random workflow instance id (dapr-agents parity), so this request
 * is pure workflow input, not an identity key. Chat-memory grouping (Spring AI's
 * {@code ChatMemory.CONVERSATION_ID}) is handled entirely by upstream advisors and never reaches
 * here.
 *
 * @param messages  seed messages (system/user) for the conversation
 * @param toolSpecs tools available to the model
 * @param options   portable chat options (model, temperature, max tokens, etc.) for the call
 */
public record AgentRequest(
    List<MessageRecord> messages, List<ToolSpec> toolSpecs, ChatOptionsSpec options) {}
