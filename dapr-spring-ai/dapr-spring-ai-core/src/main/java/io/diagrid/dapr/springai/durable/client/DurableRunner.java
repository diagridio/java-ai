package io.diagrid.dapr.springai.durable.client;

import io.diagrid.dapr.springai.durable.workflow.AgentRequest;
import io.diagrid.dapr.springai.durable.workflow.AgentResult;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.client.WorkflowFailureDetails;
import io.dapr.workflows.client.WorkflowRuntimeStatus;
import io.dapr.workflows.client.WorkflowState;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one durable {@code ChatClient} call: put the request on a workflow instance, block until it
 * completes, and return the result. Two entry points, by how the instance id was chosen:
 *
 * <ul>
 *   <li>{@link #run} — for a <b>generated</b> (random UUID) id. Schedules directly and waits; no
 *       state probe on the hot default path. This is dapr-agents parity: a random id can never
 *       collide, so a retried or duplicated call is a <em>new</em> execution.</li>
 *   <li>{@link #attachOrRun} — for a <b>caller-supplied</b> id. Probes the instance first and, if it
 *       already exists, <b>attaches</b> to it (returns the running/recorded result) instead of
 *       colliding with it. This makes a repeated call with the same id the retry/recovery mechanism.</li>
 * </ul>
 *
 * <p><b>Execution identity: dapr-agents parity.</b> The advisor generates a fresh
 * {@code UUID.randomUUID()} per call unless the caller supplies their own id. There is deliberately
 * <b>no</b> reissue dedup or content-hash derivation. Client-retry idempotency is the tool author's
 * concern — make tool activities idempotent using the per-execution {@code taskExecutionId} (see
 * {@code ToolInvokeActivity}).
 *
 * <p>Forms of recovery:
 * <ul>
 *   <li><b>Worker crash</b> — an in-flight workflow resumes from its history after a worker restart;
 *       completed activities are never re-executed. Intrinsic to durabletask; the blocking wait simply
 *       returns once a new worker finishes the instance.</li>
 *   <li><b>Wait-budget timeout</b> — if the call does not complete within the configured timeout, a
 *       {@link DurableCallTimeoutException} is thrown carrying the instance id. This is not a failure:
 *       the workflow keeps running to completion. With a caller-supplied id, repeating the same call
 *       with that id re-attaches (via {@link #attachOrRun}) and returns the result once it is ready;
 *       otherwise inspect the run via Dapr tooling (the Diagrid dashboard, the {@code dapr workflow}
 *       CLI) using that id.</li>
 * </ul>
 */
public class DurableRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DurableRunner.class);

  private final DaprWorkflowClient client;
  private final Duration completionTimeout;

  public DurableRunner(DaprWorkflowClient client, Duration completionTimeout) {
    this.client = client;
    this.completionTimeout = completionTimeout;
  }

  /**
   * Schedules the request under {@code instanceId} and the given workflow name, then blocks until the
   * workflow completes and returns the {@link AgentResult} (final text plus aggregated response
   * metadata). The name lets a per-agent workflow (named after the ChatClient bean) be used instead of
   * the generic {@code AgentWorkflow.NAME}; it must be a name registered on the worker.
   *
   * <p>For a <b>generated</b> (random UUID) id: it schedules directly, with no state probe, because a
   * random id cannot collide. Use {@link #attachOrRun} for a caller-supplied id.
   *
   * @param instanceId   a unique instance id (the advisor supplies a random UUID for this path)
   * @param request      the call rendered as workflow input
   * @param workflowName the workflow type to schedule (must be registered on the worker)
   * @throws DurableCallTimeoutException if the workflow does not complete within the configured
   *     timeout — it keeps running; inspect it via Dapr tooling using the instance id
   */
  public AgentResult run(String instanceId, AgentRequest request, String workflowName) {
    client.scheduleNewWorkflow(workflowName, request, instanceId);
    LOG.info("Scheduled durable workflow instance {} ({})", instanceId, workflowName);
    return awaitCompletion(instanceId, workflowName);
  }

  /**
   * Runs the request under a <b>caller-supplied</b> {@code instanceId}, attaching to any existing
   * instance under that id instead of colliding with it. This is what turns a repeated
   * {@code ChatClient.call()} with the same id into the retry/recovery mechanism (see
   * {@code DurableAdvisor.INSTANCE_ID_KEY}). Same key, same run — database semantics.
   *
   * <p>The instance is probed once with {@link DaprWorkflowClient#getWorkflowState}, then:
   * <ul>
   *   <li><b>completed</b> → the recorded {@link AgentResult} is returned; the workflow is <b>not</b>
   *       re-scheduled or re-run;</li>
   *   <li><b>failed / terminated</b> → the recorded failure is surfaced (an {@link IllegalStateException}
   *       with the backend details); not re-run. Purge the id or use a new one to re-attempt;</li>
   *   <li><b>running (or otherwise active: pending, suspended, continued-as-new)</b> → attach by waiting
   *       for completion, without scheduling;</li>
   *   <li><b>absent</b> → schedule it (the only path that creates), then wait.</li>
   * </ul>
   *
   * <p>The {@code request} is used only on the absent path (a fresh create); when attaching to an
   * existing instance the recorded input governs — the supplied request payload is ignored, matching
   * the "same key attaches to the same row" contract.
   *
   * <p><b>Concurrent-create race.</b> Two callers may both probe "absent" and race to create the same
   * id; the backend rejects the loser's create with an untyped "already exists" error, which is caught
   * and turned into the attach path. The only residual race — a request that is created <em>and fully
   * completed</em> by a concurrent caller within the probe→schedule gap — would re-run; it is
   * effectively impossible for an LLM-duration workload.
   *
   * @param instanceId   the caller-supplied instance id (bearer handle — guard it like a primary key)
   * @param request      the call rendered as workflow input (used only when the instance is absent)
   * @param workflowName the workflow type to schedule (must be registered on the worker)
   * @throws DurableCallTimeoutException if the (attached or created) workflow does not complete within
   *     the configured timeout — it keeps running; repeat the same call with this id to re-attach
   */
  public AgentResult attachOrRun(String instanceId, AgentRequest request, String workflowName) {
    WorkflowState existing = client.getWorkflowState(instanceId, true);
    if (!isAbsent(existing)) {
      if (existing.isCompleted()) {
        // Terminal: COMPLETED yields the recorded result, FAILED/TERMINATED surfaces the failure.
        // Either way we neither schedule nor wait — the outcome is already recorded.
        LOG.info("Attaching to terminal workflow instance {} (status {})",
            instanceId, existing.getRuntimeStatus());
        return outputOrThrow(existing, instanceId);
      }
      // Present and still active (RUNNING / PENDING / SUSPENDED / CONTINUED_AS_NEW): attach by waiting;
      // do NOT schedule. If it completes before the wait elapses, the wait just returns its output.
      LOG.info("Attaching to in-flight workflow instance {} (status {})",
          instanceId, existing.getRuntimeStatus());
      return awaitCompletion(instanceId, workflowName);
    }
    // Absent: create it. Scheduling only here (never against an existing instance) is what keeps a
    // repeat call from re-running an already-finished workflow. A concurrent caller may win the create
    // race — the backend's "already exists" error is caught below and treated as the attach path.
    try {
      client.scheduleNewWorkflow(workflowName, request, instanceId);
      LOG.info("Scheduled durable workflow instance {} ({})", instanceId, workflowName);
    } catch (RuntimeException e) {
      if (!isAlreadyExists(e)) {
        throw e;
      }
      LOG.info("Instance {} was created concurrently; attaching instead of scheduling", instanceId);
    }
    return awaitCompletion(instanceId, workflowName);
  }

  /** Blocks until the instance reaches a terminal state, then maps it to a result (or a typed timeout). */
  private AgentResult awaitCompletion(String instanceId, String workflowName) {
    WorkflowState completed;
    try {
      completed = client.waitForWorkflowCompletion(instanceId, completionTimeout, true);
    } catch (TimeoutException e) {
      throw new DurableCallTimeoutException(instanceId, workflowName, e);
    }
    if (completed == null) {
      throw new IllegalStateException("No workflow instance found: " + instanceId);
    }
    return outputOrThrow(completed, instanceId);
  }

  /**
   * Returns the workflow output only when it genuinely COMPLETED. Any other terminal state (FAILED,
   * TERMINATED, CANCELED, …) has no valid output, so {@code readOutputAs} would return null/garbage;
   * surface it as an error carrying the backend failure details instead of masking the real failure.
   *
   * <p>A COMPLETED instance whose output cannot be read as an {@link AgentResult} is a <b>foreign</b>
   * workflow — a caller-supplied id that belongs to a different workflow type. Surface that as a clear
   * error naming the id rather than a raw deserialization failure. (A foreign output that happens to be
   * structurally compatible is indistinguishable and out of scope; ids are bearer handles the app owns.)
   */
  static AgentResult outputOrThrow(WorkflowState state, String instanceId) {
    WorkflowRuntimeStatus status = state.getRuntimeStatus();
    if (status == WorkflowRuntimeStatus.COMPLETED) {
      try {
        return state.readOutputAs(AgentResult.class);
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            "Durable workflow " + instanceId + " completed but its output is not an AgentResult; this"
                + " id likely belongs to a foreign workflow instance not created by the durable"
                + " ChatClient.",
            e);
      }
    }
    WorkflowFailureDetails failure = state.getFailureDetails();
    String detail =
        failure == null
            ? "no failure details"
            : failure.getErrorType() + ": " + failure.getErrorMessage();
    throw new IllegalStateException(
        "Durable workflow " + instanceId + " ended in status " + status + " (" + detail + ")");
  }

  /**
   * Whether the probed state means "no instance under this id yet". {@link DaprWorkflowClient#getWorkflowState}
   * never returns null over gRPC; an unknown instance comes back as the proto default — runtime status
   * {@code RUNNING} while {@code isRunning()} is {@code false} ({@code isRunning} implies the instance
   * was found). That pair uniquely identifies not-found: a genuinely running instance reports
   * {@code isRunning()==true}, and every present-but-not-running state reports a status other than
   * {@code RUNNING} (COMPLETED, FAILED, PENDING, SUSPENDED, …). Verified against dapr-sdk-workflows
   * 1.18.0 and pinned by {@code DurableRunnerTest}.
   */
  static boolean isAbsent(WorkflowState state) {
    return state.getRuntimeStatus() == WorkflowRuntimeStatus.RUNNING && !state.isRunning();
  }

  /**
   * The backend signals a duplicate active instance with an untyped "already exists" error (a
   * {@code StatusRuntimeException} whose message text is the only signal — dapr's own integration
   * tests pin that text). Walk the cause chain so a wrapped occurrence is still recognized.
   */
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
