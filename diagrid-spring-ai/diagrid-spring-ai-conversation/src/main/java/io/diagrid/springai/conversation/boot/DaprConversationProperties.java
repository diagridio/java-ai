package io.diagrid.springai.conversation.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Dapr Conversation API {@code ChatModel}. Enabling/disabling the model
 * follows Spring AI's provider convention via {@code spring.ai.model.chat} (value {@code dapr}),
 * not a module-level flag.
 *
 * @param component   name of the Dapr conversation component routing LLM traffic — required, no
 *                    default: which provider a call reaches is not something to guess
 * @param contextId   conversation session id handed to the sidecar (optional)
 * @param scrubPii    whether the sidecar obfuscates PII in inputs and outputs (default false)
 * @param temperature default sampling temperature (optional; the pinned Dapr SDK transmits 0.0
 *                    when unset — see the ChatModel Javadoc)
 */
@ConfigurationProperties("dapr.spring-ai.conversation")
public record DaprConversationProperties(String component, String contextId, Boolean scrubPii,
    Double temperature) {

  public DaprConversationProperties {
    if (scrubPii == null) {
      scrubPii = false;
    }
  }
}
