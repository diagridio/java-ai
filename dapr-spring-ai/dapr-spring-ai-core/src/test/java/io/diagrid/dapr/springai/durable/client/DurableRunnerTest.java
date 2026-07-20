package io.diagrid.dapr.springai.durable.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.client.WorkflowFailureDetails;
import io.dapr.workflows.client.WorkflowRuntimeStatus;
import io.dapr.workflows.client.WorkflowState;
import io.diagrid.dapr.springai.durable.conversation.MessageRecord;
import io.diagrid.dapr.springai.durable.workflow.AgentRequest;
import io.diagrid.dapr.springai.durable.workflow.AgentResult;
import io.diagrid.dapr.springai.durable.workflow.ChatOptionsSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * dapr-agents parity: {@link DurableRunner#run} (the generated-id path) schedules under the given id,
 * waits, and returns the output — no probe, no dedup. {@link DurableRunner#attachOrRun} (the
 * caller-supplied-id path) probes first and attaches to an existing instance instead of colliding:
 * completed → recorded result (never re-run), failed → recorded failure, running → wait, absent →
 * schedule (with the concurrent-create "already exists" race folded into attach). A wait timeout
 * surfaces as {@link DurableCallTimeoutException} carrying the id. {@link DurableRunner#outputOrThrow}
 * yields output only from a genuine COMPLETED, and names the id on a foreign (undeserializable) output.
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

  // ---- attachOrRun(): a caller-supplied id attaches to an existing instance, per the contract ----

  private static FakeState absent() {
    // getWorkflowState never returns null over gRPC; an unknown id is the proto default (RUNNING,
    // isRunning()==false). See isAbsentIsTrueOnlyForTheProtoDefaultNotFoundState.
    return new FakeState(WorkflowRuntimeStatus.RUNNING, false, null, null);
  }

  @Test
  void attachSchedulesWhenInstanceAbsent() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.getWorkflowState(anyString(), anyBoolean())).thenReturn(absent());
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    assertEquals("FINAL", runner.attachOrRun("mine-1", request(), "wf").finalText());

    verify(client).scheduleNewWorkflow(eq("wf"), any(), eq("mine-1"));
    verify(client).waitForWorkflowCompletion(eq("mine-1"), any(), anyBoolean());
  }

  @Test
  void attachToRunningWaitsWithoutScheduling() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.RUNNING, true, null, null)); // genuinely running
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    assertEquals("FINAL", runner.attachOrRun("mine-2", request(), "wf").finalText());

    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
    verify(client).waitForWorkflowCompletion(eq("mine-2"), any(), anyBoolean());
  }

  /**
   * The critical regression guard (today a supplied completed id re-runs): a completed instance
   * returns its recorded result, and the runner NEVER schedules or waits — the outcome is recorded.
   */
  @Test
  void attachToCompletedReturnsRecordedResultAndNeverSchedules() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    AgentResult recorded = completed("RECORDED");
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, recorded, null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    assertSame(recorded, runner.attachOrRun("mine-3", request(), "wf"));

    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
    verify(client, never()).waitForWorkflowCompletion(anyString(), any(), anyBoolean());
  }

  @Test
  void attachToFailedSurfacesRecordedFailureAndNeverReRuns() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    WorkflowFailureDetails failure = new FakeFailure("java.lang.IllegalStateException", "provider 500");
    when(client.getWorkflowState(anyString(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.FAILED, false, null, failure));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> runner.attachOrRun("mine-4", request(), "wf"));
    assertTrue(e.getMessage().contains("FAILED"), e.getMessage());
    assertTrue(e.getMessage().contains("provider 500"), e.getMessage());

    verify(client, never()).scheduleNewWorkflow(anyString(), any(), anyString());
    verify(client, never()).waitForWorkflowCompletion(anyString(), any(), anyBoolean());
  }

  /**
   * Concurrent-create race: the probe reports absent, but a concurrent caller created the id first, so
   * the schedule throws the backend's untyped "already exists" error. This pins the message match:
   * wrapped in a cause chain AND mixed-case still routes to the attach path.
   */
  @Test
  void attachOnAlreadyExistsScheduleRaceAttachesInsteadOfFailing() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.getWorkflowState(anyString(), anyBoolean())).thenReturn(absent());
    doThrow(new RuntimeException("schedule failed", new RuntimeException("Instance ALREADY EXISTS")))
        .when(client).scheduleNewWorkflow(eq("wf"), any(), eq("mine-5"));
    when(client.waitForWorkflowCompletion(anyString(), any(), anyBoolean()))
        .thenReturn(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("FINAL"), null));

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    assertEquals("FINAL", runner.attachOrRun("mine-5", request(), "wf").finalText());
    verify(client).waitForWorkflowCompletion(eq("mine-5"), any(), anyBoolean());
  }

  /** A schedule error that is NOT "already exists" is real and must propagate (never swallowed). */
  @Test
  void attachPropagatesNonAlreadyExistsScheduleError() throws Exception {
    DaprWorkflowClient client = mock(DaprWorkflowClient.class);
    when(client.getWorkflowState(anyString(), anyBoolean())).thenReturn(absent());
    doThrow(new RuntimeException("connection refused"))
        .when(client).scheduleNewWorkflow(anyString(), any(), anyString());

    DurableRunner runner = new DurableRunner(client, Duration.ofSeconds(1));
    RuntimeException e =
        assertThrows(RuntimeException.class, () -> runner.attachOrRun("mine-6", request(), "wf"));
    assertTrue(e.getMessage().contains("connection refused"), e.getMessage());
    verify(client, never()).waitForWorkflowCompletion(anyString(), any(), anyBoolean());
  }

  /**
   * NOT-FOUND pin (the fact attachOrRun's absent-detection rests on): only the proto-default pair
   * — status RUNNING with isRunning()==false — is "absent". A genuinely running instance
   * (isRunning()==true) and every present-but-not-running status are found. Verified against
   * dapr-sdk-workflows 1.18.0.
   */
  @Test
  void isAbsentIsTrueOnlyForTheProtoDefaultNotFoundState() {
    assertTrue(DurableRunner.isAbsent(new FakeState(WorkflowRuntimeStatus.RUNNING, false, null, null)));
    assertFalse(
        DurableRunner.isAbsent(new FakeState(WorkflowRuntimeStatus.RUNNING, true, null, null)),
        "a genuinely running instance is found, not absent");
    assertFalse(
        DurableRunner.isAbsent(new FakeState(WorkflowRuntimeStatus.COMPLETED, false, completed("x"), null)));
    assertFalse(DurableRunner.isAbsent(new FakeState(WorkflowRuntimeStatus.PENDING, false, null, null)));
    assertFalse(DurableRunner.isAbsent(new FakeState(WorkflowRuntimeStatus.SUSPENDED, false, null, null)));
  }

  /**
   * Foreign-output: a COMPLETED instance whose output cannot be read as an AgentResult (a supplied id
   * pointing at a different workflow type) surfaces a clear error naming the id, not a raw
   * deserialization failure.
   */
  @Test
  void foreignCompletedOutputThrowsAClearErrorNamingTheId() {
    WorkflowState state = mock(WorkflowState.class);
    when(state.getRuntimeStatus()).thenReturn(WorkflowRuntimeStatus.COMPLETED);
    when(state.readOutputAs(AgentResult.class)).thenThrow(new RuntimeException("cannot deserialize"));

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> DurableRunner.outputOrThrow(state, "foreign-9"));
    assertTrue(e.getMessage().contains("foreign-9"), e.getMessage());
    assertTrue(e.getMessage().toLowerCase().contains("foreign"), e.getMessage());
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
