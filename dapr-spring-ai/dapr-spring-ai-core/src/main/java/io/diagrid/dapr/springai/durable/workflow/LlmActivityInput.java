package io.diagrid.dapr.springai.durable.workflow;

import io.diagrid.dapr.springai.durable.conversation.MessageRecord;
import java.util.List;

/**
 * Input to {@code LlmInvokeActivity}: the full accumulated conversation plus the tool surface and
 * model options. The orchestrator passes the conversation as serializable records; the activity
 * decodes them to Spring AI messages.
 *
 * @param conversation ordered conversation records (seed + all prior turns)
 * @param toolSpecs    tools to advertise to the model
 * @param options      portable chat options to apply on top of the provider defaults
 * @param trace        observability context (instance id / workflow name / trace carrier)
 */
public record LlmActivityInput(
    List<MessageRecord> conversation,
    List<ToolSpec> toolSpecs,
    ChatOptionsSpec options,
    ActivityTrace trace) {

  /** Convenience for standalone/test use with no observability context. */
  public LlmActivityInput(List<MessageRecord> conversation, List<ToolSpec> toolSpecs, ChatOptionsSpec options) {
    this(conversation, toolSpecs, options, ActivityTrace.NONE);
  }
}
