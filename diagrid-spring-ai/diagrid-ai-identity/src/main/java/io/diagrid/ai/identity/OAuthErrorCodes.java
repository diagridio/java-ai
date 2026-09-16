package io.diagrid.ai.identity;

/**
 * The error codes an identity rejection is reported with.
 *
 * <p>These strings are a cross-SDK contract, not an implementation detail: every Diagrid SDK puts the
 * same code in the {@code {"error":"..."}} response body, so a caller can branch on the code without
 * caring which language served the request. Never rename one here — rename it in every SDK at once,
 * or not at all.
 */
public final class OAuthErrorCodes {

  /** No {@code X-Diagrid-User-Token} header on a request that requires one. HTTP 401. */
  public static final String MISSING_TOKEN = "oauth.missing_token";

  /** Identity coordinates could not be discovered, so no token can be verified. HTTP 503. */
  public static final String NOT_CONFIGURED = "oauth.not_configured";

  /** Key material is not loaded yet, or no key matches the token's {@code kid}. HTTP 503. */
  public static final String VERIFIER_UNAVAILABLE = "oauth.verifier_unavailable";

  /** The {@code exp} claim is in the past, beyond the allowed clock skew. HTTP 401. */
  public static final String EXPIRED = "oauth.expired";

  /** The {@code iss} claim does not match the configured issuer. HTTP 401. */
  public static final String INVALID_ISSUER = "oauth.invalid_issuer";

  /** The {@code aud} claim does not contain the configured audience. HTTP 401. */
  public static final String INVALID_AUDIENCE = "oauth.invalid_audience";

  /** The signature does not verify against the selected key. HTTP 401. */
  public static final String INVALID_SIGNATURE = "oauth.invalid_signature";

  /** The token is not a well-formed JWS. HTTP 401. */
  public static final String DECODE_ERROR = "oauth.decode_error";

  /** The token is structurally valid but otherwise unusable — bad algorithm, missing claim. HTTP 401. */
  public static final String INVALID_TOKEN = "oauth.invalid_token";

  /** The token verified, but lacks a scope the app requires. HTTP 403. */
  public static final String MISSING_SCOPE = "oauth.missing_scope";

  private OAuthErrorCodes() {
  }
}
