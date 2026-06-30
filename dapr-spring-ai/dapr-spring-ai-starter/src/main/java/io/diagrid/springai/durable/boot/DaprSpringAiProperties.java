package io.diagrid.springai.durable.boot;

import io.dapr.workflows.WorkflowTaskOptions;
import io.dapr.workflows.WorkflowTaskRetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the durable Spring AI integration.
 *
 * @param enabled               whether to make ChatClient calls durable (default true)
 * @param requireConversationId strict mode: fail a call that has no conversation id instead of
 *                              falling back to a content-hash durability key (default false)
 * @param completionTimeout     how long a call blocks waiting for its workflow to complete
 * @param retry                 retry policy applied to the LLM and tool activities
 */
@ConfigurationProperties("dapr.spring-ai")
public record DaprSpringAiProperties(
    Boolean enabled, Boolean requireConversationId, Duration completionTimeout, Retry retry) {

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
    if (retry == null) {
      retry = new Retry(null, null, null, null, null);
    }
  }

  /**
   * Retry/backoff for the LLM and tool activities. A transient failure (e.g. a provider rate limit
   * or network blip) is retried by the Dapr runtime instead of failing the whole workflow; the
   * number of attempts is bounded by {@code maxAttempts}.
   *
   * @param enabled            whether activities are retried at all (default true)
   * @param maxAttempts        total attempts including the first (default 3)
   * @param firstInterval      delay before the first retry (default 1s)
   * @param backoffCoefficient multiplier applied to the interval after each attempt (default 2.0)
   * @param maxInterval        cap on the (growing) retry interval (default 30s)
   */
  public record Retry(
      Boolean enabled,
      Integer maxAttempts,
      Duration firstInterval,
      Double backoffCoefficient,
      Duration maxInterval) {

    public Retry {
      if (enabled == null) {
        enabled = true;
      }
      if (maxAttempts == null) {
        maxAttempts = 3;
      }
      if (firstInterval == null) {
        firstInterval = Duration.ofSeconds(1);
      }
      if (backoffCoefficient == null) {
        backoffCoefficient = 2.0;
      }
      if (maxInterval == null) {
        maxInterval = Duration.ofSeconds(30);
      }
    }

    /**
     * Builds the activity options carrying this retry policy.
     *
     * @return the options, or {@code null} when retries are disabled (the workflow then makes a
     *     single attempt per activity)
     */
    public WorkflowTaskOptions toWorkflowTaskOptions() {
      if (!enabled) {
        return null;
      }
      WorkflowTaskRetryPolicy policy =
          WorkflowTaskRetryPolicy.newBuilder()
              .setMaxNumberOfAttempts(maxAttempts)
              .setFirstRetryInterval(firstInterval)
              .setBackoffCoefficient(backoffCoefficient)
              .setMaxRetryInterval(maxInterval)
              .build();
      return new WorkflowTaskOptions(policy);
    }
  }
}
