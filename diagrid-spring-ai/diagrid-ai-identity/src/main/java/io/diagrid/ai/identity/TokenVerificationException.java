package io.diagrid.ai.identity;

/**
 * The token was rejected: the signature did not verify, or a claim did not satisfy the policy.
 *
 * <p>Callers branch on {@link #code()}, never on the message. The code is one of the
 * {@link OAuthErrorCodes} constants and is the same string the other Diagrid SDKs report, so a client
 * can handle a rejection identically whatever language served it. The message is for logs only, and
 * deliberately carries no token material.
 */
public class TokenVerificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  /**
   * @param code    one of the {@link OAuthErrorCodes} constants
   * @param message a short, non-sensitive explanation for logs
   */
  public TokenVerificationException(String code, String message) {
    super(message == null || message.isEmpty() ? code : message);
    this.code = code;
  }

  /**
   * @param code    one of the {@link OAuthErrorCodes} constants
   * @param message a short, non-sensitive explanation for logs
   * @param cause   the underlying JOSE failure
   */
  public TokenVerificationException(String code, String message, Throwable cause) {
    super(message == null || message.isEmpty() ? code : message, cause);
    this.code = code;
  }

  /**
   * The machine-readable reason the token was rejected.
   *
   * @return one of the {@link OAuthErrorCodes} constants
   */
  public String code() {
    return code;
  }
}
