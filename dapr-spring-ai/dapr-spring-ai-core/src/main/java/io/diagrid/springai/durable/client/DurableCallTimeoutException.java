package io.diagrid.springai.durable.client;

/**
 * Thrown when a durable {@code ChatClient} call's wait budget elapses before its workflow completes.
 *
 * <p>This is <b>not</b> a failure: the workflow is still running on the backend and will finish on its
 * own. The configured {@code completion-timeout} is only how long the calling thread is willing to
 * block.
 *
 * <p><b>Recovery.</b> If you scheduled the call under an id you own (via
 * {@code DurableAdvisor.INSTANCE_ID_KEY}), the recovery flow is: catch this, keep {@link #instanceId()},
 * and repeat the <em>same</em> {@code ChatClient} call with that id. The repeat <b>attaches</b> to the
 * still-running instance and returns its result once ready — as a normal successful call, so the full
 * advisor chain (chat memory, etc.) applies. Loop the repeat until it returns. As a fallback (or with a
 * generated id, which has no attach handle), the still-running instance can be inspected via Dapr
 * tooling — the Diagrid dashboard or the {@code dapr workflow} CLI — using {@link #instanceId()}.
 *
 * <p>Unchecked so it propagates cleanly through Spring AI's advisor chain; the durable advisor lets it
 * through unwrapped (unlike other failures, which it wraps) so callers can catch it by type and read
 * the id.
 */
public class DurableCallTimeoutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String instanceId;
  private final String workflowName;

  /**
   * @param instanceId   the workflow instance still running; use it to find the run in Dapr tooling
   * @param workflowName the workflow type it was scheduled under
   * @param cause        the underlying wait timeout
   */
  public DurableCallTimeoutException(String instanceId, String workflowName, Throwable cause) {
    super(
        "Durable ChatClient call timed out waiting for workflow instance "
            + instanceId
            + " ("
            + workflowName
            + "); the workflow is still running — the timeout is only a wait budget. Inspect the "
            + "instance via the Diagrid dashboard or `dapr workflow` CLI using this id.",
        cause);
    this.instanceId = instanceId;
    this.workflowName = workflowName;
  }

  /** The still-running workflow instance id, for correlation / lookup in Dapr tooling. */
  public String instanceId() {
    return instanceId;
  }

  /** The workflow type the instance was scheduled under. */
  public String workflowName() {
    return workflowName;
  }
}
