package io.diagrid.springai.durable.client;

import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.AgentResult;
import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.client.WorkflowFailureDetails;
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
 * {@link DaprWorkflowClient#getWorkflowState}, then act on the reported status —
 * <ul>
 *   <li><b>completed</b> → return the recorded output (reissue/dedup), no re-execution;</li>
 *   <li><b>absent</b> → create it (the only path that schedules);</li>
 *   <li><b>terminally failed</b> → per {@link FailedInstancePolicy}: surface the failure ({@code FAIL},
 *       default) or recreate ({@code RETRY});</li>
 *   <li><b>active</b> → attach by waiting, without scheduling.</li>
 * </ul>
 * then {@link DaprWorkflowClient#waitForWorkflowCompletion}. Scheduling only when the id is absent is
 * what keeps a reissue from re-running an already-finished workflow.
 *
 * <p>The residual race — a concurrent identical request that created <em>and fully completed</em> in
 * the probe→schedule gap — is effectively impossible for an LLM workload; a concurrent create that is
 * still active is handled defensively (the "already exists" collision is treated as the attach path).
 * The backend behavior on a duplicate create is verified empirically by the crash-recovery
 * integration test.
 */
public class DurableRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DurableRunner.class);

  private final DaprWorkflowClient client;
  private final InstanceIdDerivation idDerivation;
  private final Duration completionTimeout;
  private final FailedInstancePolicy failedInstancePolicy;

  /** Uses {@link FailedInstancePolicy#FAIL} — a reissue onto a failed instance surfaces the failure. */
  public DurableRunner(
      DaprWorkflowClient client, InstanceIdDerivation idDerivation, Duration completionTimeout) {
    this(client, idDerivation, completionTimeout, FailedInstancePolicy.FAIL);
  }

  /**
   * @param failedInstancePolicy what to do when a reissue's id maps to a terminally-failed workflow
   */
  public DurableRunner(
      DaprWorkflowClient client,
      InstanceIdDerivation idDerivation,
      Duration completionTimeout,
      FailedInstancePolicy failedInstancePolicy) {
    this.client = client;
    this.idDerivation = idDerivation;
    this.completionTimeout = completionTimeout;
    this.failedInstancePolicy = failedInstancePolicy;
  }

  /** The deterministic instance id for a request (the implicit durability handle). */
  public String instanceId(AgentRequest request) {
    return idDerivation.deriveInstanceId(request);
  }

  /**
   * Runs the request durably under the generic {@link AgentWorkflow#NAME} workflow type.
   *
   * @throws TimeoutException if the workflow does not complete within the configured timeout
   */
  public AgentResult run(AgentRequest request) throws TimeoutException {
    return run(request, AgentWorkflow.NAME);
  }

  /**
   * Runs the request durably under the given workflow name and returns the {@link AgentResult} (final
   * text plus aggregated response metadata). The name lets a per-agent workflow (named after the
   * ChatClient bean) be used instead of the generic type; it must be a name registered on the worker.
   *
   * @throws TimeoutException if the workflow does not complete within the configured timeout
   */
  public AgentResult run(AgentRequest request, String workflowName) throws TimeoutException {
    String instanceId = idDerivation.deriveInstanceId(request);

    // Probe, then act on what we found. getWorkflowState never returns null (an unknown instance
    // comes back as a not-found state — see isAbsent), so branch on the reported status.
    WorkflowState existing = client.getWorkflowState(instanceId, true);
    WorkflowRuntimeStatus status = existing == null ? null : existing.getRuntimeStatus();

    // Reuse a completed run without re-executing — the reissue/dedup path.
    if (status == WorkflowRuntimeStatus.COMPLETED) {
      LOG.info("Attaching to completed workflow instance {}", instanceId);
      return existing.readOutputAs(AgentResult.class);
    }

    if (existing == null || isAbsent(existing)) {
      // No instance under this id yet: create it. Scheduling *only* here (never against an existing
      // instance) shrinks the duplicate-run race to a concurrent identical request that both created
      // AND fully completed within the probe->schedule gap — effectively impossible for an LLM call.
      schedule(workflowName, request, instanceId);
    } else if (isTerminalFailure(existing)) {
      // A prior run under this id failed / was terminated. The reissue behavior is configurable.
      if (failedInstancePolicy == FailedInstancePolicy.RETRY) {
        LOG.info("Instance {} previously {}; recreating (RETRY policy)", instanceId, status);
        schedule(workflowName, request, instanceId);
      } else {
        LOG.info("Instance {} previously {}; surfacing the failure (FAIL policy)", instanceId, status);
        return outputOrThrow(existing, instanceId);
      }
    } else {
      // Present and still active (RUNNING / PENDING / SUSPENDED / CONTINUED_AS_NEW): attach, do NOT
      // schedule. If it completes before our wait, the wait just returns it — nothing re-runs.
      LOG.info("Attaching to in-flight workflow instance {} ({})", instanceId, status);
    }

    WorkflowState completed = client.waitForWorkflowCompletion(instanceId, completionTimeout, true);
    if (completed == null) {
      throw new IllegalStateException("No workflow instance found after scheduling: " + instanceId);
    }
    return outputOrThrow(completed, instanceId);
  }

  /**
   * Returns the workflow output only when it genuinely COMPLETED. Any other terminal state (FAILED,
   * TERMINATED, CANCELED, …) has no valid output, so {@code readOutputAs} would return null/garbage;
   * surface it as an error carrying the backend failure details instead of masking the real failure.
   */
  static AgentResult outputOrThrow(WorkflowState state, String instanceId) {
    WorkflowRuntimeStatus status = state.getRuntimeStatus();
    if (status == WorkflowRuntimeStatus.COMPLETED) {
      return state.readOutputAs(AgentResult.class);
    }
    WorkflowFailureDetails failure = state.getFailureDetails();
    String detail =
        failure == null
            ? "no failure details"
            : failure.getErrorType() + ": " + failure.getErrorMessage();
    throw new IllegalStateException(
        "Durable workflow " + instanceId + " ended in status " + status + " (" + detail + ")");
  }

  // Schedule a new run, treating an "already exists" collision (a concurrent identical request that
  // created it first) as the attach path; any other error is real and propagates.
  private void schedule(String workflowName, AgentRequest request, String instanceId) {
    try {
      client.scheduleNewWorkflow(workflowName, request, instanceId);
      LOG.info("Scheduled durable workflow instance {} ({})", instanceId, workflowName);
    } catch (RuntimeException e) {
      if (!isAlreadyExists(e)) {
        throw e;
      }
      LOG.info("Instance {} already exists; attaching instead of creating", instanceId);
    }
  }

  /**
   * Whether the probed state means "no instance under this id yet". {@code getWorkflowState} never
   * returns null; an unknown instance reports the proto-default status {@code RUNNING} while
   * {@code isRunning()} is false ({@code isRunning} implies {@code isInstanceFound}). That pair
   * uniquely identifies a not-found instance: a genuinely running one reports
   * {@code isRunning()==true}, and every other present state reports a status other than
   * {@code RUNNING}. Verified against dapr-sdk-workflows 1.19 and pinned by {@code CrashRecoveryIT}.
   */
  static boolean isAbsent(WorkflowState state) {
    return state.getRuntimeStatus() == WorkflowRuntimeStatus.RUNNING && !state.isRunning();
  }

  // A prior run under this id ended in a terminal failure (COMPLETED is handled separately, as dedup).
  static boolean isTerminalFailure(WorkflowState state) {
    WorkflowRuntimeStatus status = state.getRuntimeStatus();
    return status == WorkflowRuntimeStatus.FAILED
        || status == WorkflowRuntimeStatus.TERMINATED
        || status == WorkflowRuntimeStatus.CANCELED;
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
