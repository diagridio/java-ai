package io.diagrid.springai.durable.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.client.WorkflowFailureDetails;
import io.dapr.workflows.client.WorkflowRuntimeStatus;
import io.dapr.workflows.client.WorkflowState;
import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentResult;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the two things that make the probe-then-act loop correct: reading output only from a genuine
 * COMPLETED state, and detecting "absent" so the runner schedules exactly once. The run() branches
 * (schedule-only-when-absent, attach-when-active, FAIL vs RETRY on a failed instance) are driven with
 * a mocked {@link DaprWorkflowClient}.
 */
class DurableRunnerTest {

  private static AgentRequest request() {
    return new AgentRequest(List.of(MessageRecord.user("hi")), List.of(), ChatOptionsSpec.empty());
  }

  private static FakeState state(WorkflowRuntimeStatus status, boolean running) {
    return new FakeState(status, running, null, null);
  }

  // ---- outputOrThrow: only a genuine COMPLETED yields output ----

  @Test
  void completedReturnsOutput() {
    AgentResult output = new AgentResult("FINAL", "stop", null, "gpt-4o-mini", null, 1);
    WorkflowState workflowState = new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null);
    assertEquals("FINAL", DurableRunner.outputOrThrow(workflowState, "dsa-c-x-abcd1234").finalText());
  }

  @Test
  void failedThrowsWithFailureDetails() {
    WorkflowFailureDetails failure =
        new FakeFailure("java.lang.IllegalStateException", "provider returned 500");
    WorkflowState workflowState = new FakeState(WorkflowRuntimeStatus.FAILED, false, null, failure);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> DurableRunner.outputOrThrow(workflowState, "dsa-c-x-abcd1234"));
    assertTrue(e.getMessage().contains("FAILED"), e.getMessage());
    assertTrue(e.getMessage().contains("provider returned 500"), e.getMessage());
    assertTrue(e.getMessage().contains("dsa-c-x-abcd1234"), e.getMessage());
  }

  @Test
  void terminatedWithoutDetailsStillThrows() {
    WorkflowState workflowState = new FakeState(WorkflowRuntimeStatus.TERMINATED, false, null, null);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> DurableRunner.outputOrThrow(workflowState, "inst-1"));
    assertTrue(e.getMessage().contains("TERMINATED"), e.getMessage());
    assertTrue(e.getMessage().contains("no failure details"), e.getMessage());
  }

  // ---- absent / terminal-failure detection ----

  @Test
  void isAbsentDetectsNotFoundStateOnly() {
    // not found: proto-default RUNNING but isRunning() false (isRunning implies isInstanceFound).
    assertTrue(DurableRunner.isAbsent(state(WorkflowRuntimeStatus.RUNNING, false)));
    assertFalse(DurableRunner.isAbsent(state(WorkflowRuntimeStatus.RUNNING, true)), "genuinely running");
    assertFalse(DurableRunner.isAbsent(state(WorkflowRuntimeStatus.PENDING, false)), "present, pending");
    assertFalse(DurableRunner.isAbsent(state(WorkflowRuntimeStatus.COMPLETED, false)));
  }

  @Test
  void isTerminalFailureMatchesFailedTerminatedCanceledOnly() {
    assertTrue(DurableRunner.isTerminalFailure(state(WorkflowRuntimeStatus.FAILED, false)));
    assertTrue(DurableRunner.isTerminalFailure(state(WorkflowRuntimeStatus.TERMINATED, false)));
    assertTrue(DurableRunner.isTerminalFailure(state(WorkflowRuntimeStatus.CANCELED, false)));
    assertFalse(DurableRunner.isTerminalFailure(state(WorkflowRuntimeStatus.COMPLETED, false)));
    assertFalse(DurableRunner.isTerminalFailure(state(WorkflowRuntimeStatus.RUNNING, true)));
  }

  // ---- run() branches ----

  @Test
  void absentSchedulesThenReturnsCompletedOutput() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult output = new AgentResult("FINAL", "stop", null, "m", null, 1);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(state(WorkflowRuntimeStatus.RUNNING, false)); // absent
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null));

    DurableRunner runner = new DurableRunner(client, new InstanceIdDerivation(), Duration.ofSeconds(1));

    assertEquals("FINAL", runner.run(request(), "wf").finalText());
    verify(client).scheduleNewWorkflow(eq("wf"), any(), anyString());
  }

  @Test
  void activeInstanceAttachesWithoutScheduling() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult output = new AgentResult("FINAL", "stop", null, "m", null, 1);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(state(WorkflowRuntimeStatus.RUNNING, true)); // genuinely in-flight
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null));

    DurableRunner runner = new DurableRunner(client, new InstanceIdDerivation(), Duration.ofSeconds(1));

    assertEquals("FINAL", runner.run(request(), "wf").finalText());
    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
  }

  @Test
  void completedInstanceShortCircuitsWithoutScheduleOrWait() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult output = new AgentResult("CACHED", "stop", null, "m", null, 1);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null));

    DurableRunner runner = new DurableRunner(client, new InstanceIdDerivation(), Duration.ofSeconds(1));

    assertEquals("CACHED", runner.run(request(), "wf").finalText());
    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
    verify(client, never()).waitForWorkflowCompletion(anyString(), any(), anyBoolean());
  }

  @Test
  void failedInstanceWithFailPolicyThrowsWithoutRescheduling() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.FAILED, false, null, new FakeFailure("E", "boom")));

    DurableRunner runner =
        new DurableRunner(
            client, new InstanceIdDerivation(), Duration.ofSeconds(1), FailedInstancePolicy.FAIL);

    assertThrows(IllegalStateException.class, () -> runner.run(request(), "wf"));
    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
  }

  @Test
  void failedInstanceWithRetryPolicyReschedules() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult output = new AgentResult("RETRIED", "stop", null, "m", null, 1);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.FAILED, false, null, new FakeFailure("E", "boom")));
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null));

    DurableRunner runner =
        new DurableRunner(
            client, new InstanceIdDerivation(), Duration.ofSeconds(1), FailedInstancePolicy.RETRY);

    assertEquals("RETRIED", runner.run(request(), "wf").finalText());
    verify(client).scheduleNewWorkflow(eq("wf"), any(), anyString());
  }

  /** Minimal {@link WorkflowState}: status, isRunning, output, and failure carry meaning. */
  private record FakeState(
      WorkflowRuntimeStatus status, boolean running, AgentResult output, WorkflowFailureDetails failure)
      implements WorkflowState {
    @Override public WorkflowRuntimeStatus getRuntimeStatus() { return status; }
    @Override public boolean isRunning() { return running; }
    @Override public WorkflowFailureDetails getFailureDetails() { return failure; }
    @SuppressWarnings("unchecked")
    @Override public <T> T readOutputAs(Class<T> type) { return (T) output; }
    @Override public boolean isCompleted() { return status == WorkflowRuntimeStatus.COMPLETED; }
    @Override public String getSerializedOutput() { return null; }
    @Override public String getName() { return "test"; }
    @Override public String getWorkflowId() { return "test"; }
    @Override public Instant getCreatedAt() { return null; }
    @Override public Instant getLastUpdatedAt() { return null; }
    @Override public String getSerializedInput() { return null; }
    @Override public <T> T readInputAs(Class<T> type) { return null; }
  }

  private record FakeFailure(String errorType, String errorMessage)
      implements WorkflowFailureDetails {
    @Override public String getErrorType() { return errorType; }
    @Override public String getErrorMessage() { return errorMessage; }
    @Override public String getStackTrace() { return ""; }
  }
}
