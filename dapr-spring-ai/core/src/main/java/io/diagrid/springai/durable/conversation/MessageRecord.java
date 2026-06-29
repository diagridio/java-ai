package io.diagrid.springai.durable.conversation;

import java.util.List;

/**
 * Flat, role-discriminated, serializable projection of a Spring AI {@code Message}.
 *
 * <p>One record models all four message kinds; unused fields are {@code null} for a given role:
 * <ul>
 *   <li>{@link Role#SYSTEM} / {@link Role#USER}: {@code text} only.</li>
 *   <li>{@link Role#ASSISTANT}: {@code text} plus optional {@code toolCalls}.</li>
 *   <li>{@link Role#TOOL}: {@code toolResponses} only.</li>
 * </ul>
 *
 * <p>This is the on-the-wire shape for workflow input and activity results. It intentionally does
 * not depend on Spring AI types so payloads are immune to Spring AI minor-version churn;
 * conversion to/from Spring AI {@code Message} happens in {@link MessageCodec}, inside activities.
 *
 * @param role          conversation role discriminator
 * @param text          textual content (SYSTEM/USER/ASSISTANT), may be {@code null}
 * @param toolCalls     tool calls requested by the model (ASSISTANT), may be {@code null} or empty
 * @param toolResponses tool results (TOOL), may be {@code null} or empty
 */
public record MessageRecord(
    Role role,
    String text,
    List<ToolCallRecord> toolCalls,
    List<ToolResponseRecord> toolResponses) {

  public static MessageRecord system(String text) {
    return new MessageRecord(Role.SYSTEM, text, null, null);
  }

  public static MessageRecord user(String text) {
    return new MessageRecord(Role.USER, text, null, null);
  }

  public static MessageRecord assistant(String text, List<ToolCallRecord> toolCalls) {
    return new MessageRecord(Role.ASSISTANT, text, toolCalls, null);
  }

  public static MessageRecord tool(List<ToolResponseRecord> toolResponses) {
    return new MessageRecord(Role.TOOL, null, null, toolResponses);
  }
}
