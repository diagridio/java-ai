package io.diagrid.springai.durable.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentResult;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * dapr-agents parity: {@link DurableRunner#run} schedules under the given id, waits, and returns the
 * output — no probe, no dedup. A wait timeout surfaces as {@link DurableCallTimeoutException} carrying
 * the id (no in-app collect-by-id). {@link DurableRunner#outputOrThrow} yields output only from a
 * genuine COMPLETED.
 */
class DurableRunnerTest {

  private static AgentRequest request() {
    return new AgentRequest(List.of(MessageRecord.user("hi")), List.of(), ChatOptionsSpec.empty());
  }

  private static AgentResult completed(String text) {
    return new AgentResult(text, "stop", null, "m", null, 1);
  }

  // ---- outputOrThrow: only a genuine COMPLETED yields output ----

  @Test
  void completedReturnsOutput() {
    WorkflowState workflowState = new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null);
    assertEquals("FINAL", DurableRunner.outputOrThrow(workflowState, "inst-1").finalText());
  }

  @Test
  void failedThrowsWithFailureDetails() {
    WorkflowFailureDetails failure =
        new FakeFailure("java.lang.IllegalStateException", "provider returned 500");
    WorkflowState workflowState = new FakeState(WorkflowRuntimeStatus.FAILED, false, null, failure);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> DurableRunner.outputOrThrow(workflowState, "inst-1"));
    assertTrue(e.getMessage().contains("FAILED"), e.getMessage());
    assertTrue(e.getMessage().contains("provider returned 500"), e.getMessage());
    assertTrue(e.getMessage().contains("inst-1"), e.getMessage());
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

  // ---- run(): schedule under the given id, wait, return ----

  @Test
  void runSchedulesUnderTheGivenIdAndReturnsOutput() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));

    assertEquals("FINAL", runner.run("inst-42", request(), "wf").finalText());
    verify(client).scheduleNewWorkflow(eq("wf"), any(), eq("inst-42"));
  }

  @Test
  void runNeverProbesStateFirst() throws Exception {
    // Parity: run() schedules directly — it must not pre-probe (that was the deleted dedup machinery).
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null));

    new DurableRunner(client, Duration.ofSeconds(1)).run("inst-1", request(), "wf");

    verify(client, never()).getWorkflowState(anyString(), anyBoolean());
  }

  @Test
  void runTimeoutThrowsTypedExceptionCarryingTheId() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenThrow(new TimeoutException("waited too long"));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));

    DurableCallTimeoutException e =
        assertThrows(DurableCallTimeoutException.class, () -> runner.run("inst-77", request(), "wf"));
    assertEquals("inst-77", e.instanceId());
    assertEquals("wf", e.workflowName());
    assertTrue(e.getMessage().contains("still running"), e.getMessage());
  }

  /**
   * The parity regression guard: two identical back-to-back calls must schedule two DIFFERENT
   * instances — dedup must not quietly survive. (The id is supplied by the caller; the runner must
   * schedule exactly the id it is handed, so distinct ids yield distinct schedules.)
   */
  @Test
  void identicalCallsScheduleDistinctInstances() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("X"), null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    String idA = java.util.UUID.randomUUID().toString();
    String idB = java.util.UUID.randomUUID().toString();
    runner.run(idA, request(), "wf");
    runner.run(idB, request(), "wf");

    assertNotEquals(idA, idB);
    ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
    verify(client, org.mockito.Mockito.times(2)).scheduleNewWorkflow(eq("wf"), any(), ids.capture());
    assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1), "no dedup: distinct instances");
  }

  @Test
  void runReturnsTheDeserializedOutputInstance() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult output = completed("FINAL");
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, output, null));

    AgentResult returned = new DurableRunner(client, Duration.ofSeconds(1)).run("i", request(), "wf");
    assertSame(output, returned);
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
    @Override public boolean isCompleted() {
      return status == WorkflowRuntimeStatus.COMPLETED
          || status == WorkflowRuntimeStatus.FAILED
          || status == WorkflowRuntimeStatus.TERMINATED
          || status == WorkflowRuntimeStatus.CANCELED;
    }
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
