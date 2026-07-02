package io.diagrid.springai.durable.client;

/**
 * What {@link DurableRunner} does when a reissued request's deterministic instance id maps to a prior
 * workflow that ended in a terminal <em>failure</em> ({@code FAILED} / {@code TERMINATED} /
 * {@code CANCELED}). A {@code COMPLETED} run is always reused (dedup); this policy only governs
 * terminal failures.
 */
public enum FailedInstancePolicy {

  /**
   * Surface the recorded failure without re-running (default). The reissue reports the same error, so
   * a deterministically-failing request is not re-executed on every retry.
   */
  FAIL,

  /** Recreate the workflow under the same id — the reissue is a fresh attempt. */
  RETRY
}
