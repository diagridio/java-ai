package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.dapr.workflows.WorkflowTaskOptions;
import io.dapr.workflows.WorkflowTaskRetryPolicy;
import io.diagrid.springai.durable.boot.DaprSpringAiProperties.Retry;
import io.diagrid.springai.durable.client.FailedInstancePolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DaprSpringAiPropertiesTest {

  @Test
  void failedInstancePolicyDefaultsToFail() {
    assertEquals(
        FailedInstancePolicy.FAIL,
        new DaprSpringAiProperties(null, null, null, null, null).failedInstancePolicy());
  }

  @Test
  void retryDefaultsAreAppliedWhenUnset() {
    Retry retry = new DaprSpringAiProperties(null, null, null, null, null).retry();
    WorkflowTaskOptions options = retry.toWorkflowTaskOptions();
    assertNotNull(options, "retries are on by default");
    WorkflowTaskRetryPolicy policy = options.getRetryPolicy();
    assertEquals(3, policy.getMaxNumberOfAttempts());
    assertEquals(Duration.ofSeconds(1), policy.getFirstRetryInterval());
    assertEquals(2.0, policy.getBackoffCoefficient());
    assertEquals(Duration.ofSeconds(30), policy.getMaxRetryInterval());
  }

  @Test
  void retryHonorsConfiguredValues() {
    Retry retry = new Retry(true, 5, Duration.ofSeconds(2), 3.0, Duration.ofSeconds(10));
    WorkflowTaskRetryPolicy policy = retry.toWorkflowTaskOptions().getRetryPolicy();
    assertEquals(5, policy.getMaxNumberOfAttempts());
    assertEquals(Duration.ofSeconds(2), policy.getFirstRetryInterval());
    assertEquals(3.0, policy.getBackoffCoefficient());
    assertEquals(Duration.ofSeconds(10), policy.getMaxRetryInterval());
  }

  @Test
  void retryDisabledYieldsNoOptions() {
    Retry retry = new Retry(false, null, null, null, null);
    assertNull(retry.toWorkflowTaskOptions(), "disabled retry must produce no activity options");
  }
}
