package io.diagrid.springai.durable.boot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the durable Spring AI integration.
 *
 * @param enabled               whether to make ChatClient calls durable (default true)
 * @param requireConversationId strict mode: fail a call that has no conversation id instead of
 *                              falling back to a content-hash durability key (default false)
 * @param completionTimeout     how long a call blocks waiting for its workflow to complete
 */
@ConfigurationProperties("dapr.spring-ai")
public record DaprSpringAiProperties(
    Boolean enabled, Boolean requireConversationId, Duration completionTimeout) {

  public DaprSpringAiProperties {
    if (enabled == null) {
      enabled = true;
    }
    if (requireConversationId == null) {
      requireConversationId = false;
    }
    if (completionTimeout == null) {
      completionTimeout = Duration.ofMinutes(5);
    }
  }
}
