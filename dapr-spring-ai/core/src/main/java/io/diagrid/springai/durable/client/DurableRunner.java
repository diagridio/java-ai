package io.diagrid.springai.durable.client;

import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.client.WorkflowRuntimeStatus;
import io.dapr.workflows.client.WorkflowState;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one durable {@code ChatClient} call: derive the deterministic instance id, attach to the
 * existing workflow if present, otherwise create it, then block on completion and return the result.
 *
 * <p>The deterministic id is the entire durability mechanism. Recovery uses <b>probe-then-act</b>
 * because the SDK exposes no {@code InstanceAlreadyExists} signal nor an id-reuse policy: probe with
 * {@link DaprWorkflowClient#getWorkflowState}, create only when absent, then
 * {@link DaprWorkflowClient#waitForWorkflowCompletion}. A reissued identical request computes the
 * same id and attaches to the in-flight or completed instance instead of starting a new one — no
 * re-execution of completed activities.
 *
 * <p>The narrow probe-miss race (two identical requests both create the same id concurrently) is
 * handled defensively: a failed create is logged and we proceed to wait, since either the instance
 * now exists (the wait succeeds) or it does not (the wait times out). The exact backend behavior on
 * a duplicate create is verified empirically by the crash-recovery integration test.
 */
public final class DurableRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DurableRunner.class);

  private final DaprWorkflowClient client;
  private final InstanceIdDerivation idDerivation;
  private final Duration completionTimeout;

  public DurableRunner(
      DaprWorkflowClient client, InstanceIdDerivation idDerivation, Duration completionTimeout) {
    this.client = client;
    this.idDerivation = idDerivation;
    this.completionTimeout = completionTimeout;
  }

  /** The deterministic instance id for a request (the implicit durability handle). */
  public String instanceId(AgentRequest request) {
    return idDerivation.deriveInstanceId(request);
  }

  /**
   * Runs the request durably and returns the final assistant text.
   *
   * @throws TimeoutException if the workflow does not complete within the configured timeout
   */
  public String run(AgentRequest request) throws TimeoutException {
    String instanceId = idDerivation.deriveInstanceId(request);

    // Already completed? Return its result without re-running. This is the reissue/dedup path. It is
    // safe despite getWorkflowState returning a non-null default state for unknown instances: an
    // absent instance never reports COMPLETED, so this only short-circuits genuine completions.
    // (The backend only rejects duplicate *active* ids; a completed id would otherwise re-run.)
    WorkflowState existing = client.getWorkflowState(instanceId, true);
    if (existing != null && existing.getRuntimeStatus() == WorkflowRuntimeStatus.COMPLETED) {
      LOG.info("Attaching to completed workflow instance {}", instanceId);
      return existing.readOutputAs(String.class);
    }

    // Otherwise schedule, treating an "already exists" collision (an in-flight duplicate) as the
    // attach path; any other error is real and propagates rather than masking as a timeout.
    try {
      client.scheduleNewWorkflow(AgentWorkflow.NAME, request, instanceId);
      LOG.info("Scheduled new durable workflow instance {}", instanceId);
    } catch (RuntimeException e) {
      if (!isAlreadyExists(e)) {
        throw e;
      }
      LOG.info("Instance {} already running; attaching instead of creating", instanceId);
    }

    WorkflowState completed = client.waitForWorkflowCompletion(instanceId, completionTimeout, true);
    if (completed == null) {
      throw new IllegalStateException("No workflow instance found after scheduling: " + instanceId);
    }
    return completed.readOutputAs(String.class);
  }

  /** The backend signals a duplicate active instance with an "already exists" error message. */
  private static boolean isAlreadyExists(RuntimeException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.toLowerCase().contains("already exists")) {
        return true;
      }
    }
    return false;
  }
}
