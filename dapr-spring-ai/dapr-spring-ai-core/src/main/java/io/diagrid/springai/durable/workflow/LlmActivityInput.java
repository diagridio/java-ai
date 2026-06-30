package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import java.util.List;

/**
 * Input to {@code LlmInvokeActivity}: the full accumulated conversation plus the tool surface and
 * model options. The orchestrator passes the conversation as serializable records; the activity
 * decodes them to Spring AI messages.
 *
 * @param conversation ordered conversation records (seed + all prior turns)
 * @param toolSpecs    tools to advertise to the model
 * @param model        model identifier
 * @param temperature  sampling temperature, or {@code null} for the provider default
 */
public record LlmActivityInput(
    List<MessageRecord> conversation, List<ToolSpec> toolSpecs, String model, Double temperature) {
}
