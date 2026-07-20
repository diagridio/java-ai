package io.diagrid.dapr.springai.durable.conversation;

/**
 * Conversation role discriminator for {@link MessageRecord}.
 *
 * <p>Deliberately a flat enum rather than a polymorphic {@code Message} subtype hierarchy: workflow
 * and activity payloads are serialized by durabletask's Jackson 2 converter, and a flat,
 * discriminated record round-trips cleanly without any polymorphic type handling.
 */
public enum Role {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL
}
