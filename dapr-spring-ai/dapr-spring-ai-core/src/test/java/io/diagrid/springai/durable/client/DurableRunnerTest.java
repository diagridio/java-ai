package io.diagrid.springai.durable.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dapr.workflows.client.WorkflowFailureDetails;
import io.dapr.workflows.client.WorkflowRuntimeStatus;
import io.dapr.workflows.client.WorkflowState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The runner must never read output from a workflow that did not COMPLETE: a FAILED/TERMINATED
 * instance has no valid output, so {@code readOutputAs} would hand back null/garbage. Instead the
 * runner surfaces the terminal status and the backend failure details.
 */
class DurableRunnerTest {

  @Test
  void completedReturnsOutput() {
    WorkflowState state = new FakeState(WorkflowRuntimeStatus.COMPLETED, "FINAL", null);
    assertEquals("FINAL", DurableRunner.outputOrThrow(state, "dsa-c-x-abcd1234"));
  }

  @Test
  void failedThrowsWithFailureDetails() {
    WorkflowFailureDetails failure =
        new FakeFailure("java.lang.IllegalStateException", "provider returned 500");
    WorkflowState state = new FakeState(WorkflowRuntimeStatus.FAILED, "garbage", failure);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> DurableRunner.outputOrThrow(state, "dsa-c-x-abcd1234"));
    assertTrue(e.getMessage().contains("FAILED"), e.getMessage());
    assertTrue(e.getMessage().contains("provider returned 500"), e.getMessage());
    assertTrue(e.getMessage().contains("dsa-c-x-abcd1234"), e.getMessage());
  }

  @Test
  void terminatedWithoutDetailsStillThrows() {
    WorkflowState state = new FakeState(WorkflowRuntimeStatus.TERMINATED, null, null);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> DurableRunner.outputOrThrow(state, "inst-1"));
    assertTrue(e.getMessage().contains("TERMINATED"), e.getMessage());
    assertTrue(e.getMessage().contains("no failure details"), e.getMessage());
  }

  /** Minimal {@link WorkflowState}: only the fields {@code outputOrThrow} reads carry meaning. */
  private record FakeState(WorkflowRuntimeStatus status, String output, WorkflowFailureDetails failure)
      implements WorkflowState {
    @Override public WorkflowRuntimeStatus getRuntimeStatus() { return status; }
    @Override public WorkflowFailureDetails getFailureDetails() { return failure; }
    @SuppressWarnings("unchecked")
    @Override public <T> T readOutputAs(Class<T> type) { return (T) output; }
    @Override public String getSerializedOutput() { return output; }
    @Override public boolean isCompleted() { return status == WorkflowRuntimeStatus.COMPLETED; }
    @Override public boolean isRunning() { return status == WorkflowRuntimeStatus.RUNNING; }
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
