package io.diagrid.ai.identity;

import java.util.Map;

/**
 * Turns a raw bearer token into its verified claims, or refuses it.
 *
 * <p>The abstraction every framework adapter is written against, so an adapter never names a
 * particular verification strategy: {@link JwksVerifier} is the one this module ships, and a
 * deployment whose key material arrives some other way implements this instead. It is also the seam
 * tests substitute at.
 *
 * <p>Implementations must be thread-safe and fail-closed: every token that is not positively
 * verified raises, and no path returns a partially checked result.
 */
public interface TokenVerifier {

  /**
   * Verifies a raw bearer token and returns its decoded payload.
   *
   * @param rawToken the token, with any {@code Bearer } prefix already stripped
   * @return the decoded claims, unmodifiable
   * @throws TokenVerificationException when the token is rejected; {@link
   *     TokenVerificationException#code()} says why
   * @throws VerifierNotReadyException when key material is unavailable, so the token could be neither
   *     accepted nor rejected
   */
  Map<String, Object> verify(String rawToken);
}
