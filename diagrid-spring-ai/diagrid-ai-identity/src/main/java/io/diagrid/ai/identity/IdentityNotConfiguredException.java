package io.diagrid.ai.identity;

/**
 * No issuer could be determined, so there is nothing to verify tokens against.
 *
 * <p>Thrown by {@link JwksVerifier#build(OAuthConfig)} when every source came up empty: nothing set
 * on the {@link OAuthConfig}, no sidecar metadata endpoint either locally or remotely, and no
 * {@code DIAGRID_DP_SENTRY_ISSUER} in the environment. This is a deployment mistake rather than a
 * request-level failure, so adapters answer 503 and keep rejecting until the app is configured —
 * never fall through to serving the request unauthenticated.
 */
public class IdentityNotConfiguredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message which sources were consulted, and how to supply the missing configuration
   */
  public IdentityNotConfiguredException(String message) {
    super(message);
  }
}
