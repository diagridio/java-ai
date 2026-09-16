package io.diagrid.ai.identity;

/**
 * Key material was unavailable, so the token could be neither accepted nor rejected.
 *
 * <p>Distinct from {@link TokenVerificationException} because the cause is on this side: the JWKS
 * endpoint is unreachable, or no published key matches the token's {@code kid}. Adapters answer 503
 * rather than 401 — the caller's credential may be perfectly good, and telling them it is invalid
 * would send them off to re-authenticate for nothing.
 */
public class VerifierNotReadyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what was unavailable
   */
  public VerifierNotReadyException(String message) {
    super(message);
  }

  /**
   * @param message what was unavailable
   * @param cause   the underlying retrieval failure
   */
  public VerifierNotReadyException(String message, Throwable cause) {
    super(message, cause);
  }
}
