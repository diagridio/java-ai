package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.ToolResponseRecord;
import java.util.List;

/**
 * Serializable result of one {@code ToolInvokeActivity} execution.
 *
 * @param id           tool-call id this answers (matches the requesting {@link ToolCallRecord#id()})
 * @param name         tool name that produced the result
 * @param responseData tool output, serialized to a string
 */
public record ToolResult(String id, String name, String responseData) {

  /**
   * Groups a batch of tool results (all answering one assistant turn's tool calls) into the single
   * {@code TOOL} conversation record Spring AI expects to follow that assistant turn.
   */
  public static MessageRecord toMessageRecord(List<ToolResult> batch) {
    List<ToolResponseRecord> responses =
        batch.stream()
            .map(r -> new ToolResponseRecord(r.id(), r.name(), r.responseData()))
            .toList();
    return MessageRecord.tool(responses);
  }
}
