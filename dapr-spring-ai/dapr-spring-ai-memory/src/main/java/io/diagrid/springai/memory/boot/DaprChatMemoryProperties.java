package io.diagrid.springai.memory.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Dapr-backed Spring AI chat memory.
 *
 * @param enabled    whether the Dapr chat-memory repository is active (default true)
 * @param statestore Dapr state store component holding conversation history (default
 *                   {@code agent-memory} — the memory store Catalyst provides out of the box)
 * @param agentName  namespace for the state-store keys, matching the Dapr Agents key format
 *                   {@code {agentName}:_memory_{conversationId}} (default {@code default})
 */
@ConfigurationProperties("dapr.spring-ai.memory")
public record DaprChatMemoryProperties(Boolean enabled, String statestore, String agentName) {

  public DaprChatMemoryProperties {
    if (enabled == null) {
      enabled = true;
    }
    if (statestore == null || statestore.isBlank()) {
      statestore = "agent-memory";
    }
    if (agentName == null || agentName.isBlank()) {
      agentName = "default";
    }
  }
}
