package io.diagrid.ai.identity;

import java.util.Map;

/**
 * The caller's token for the request in flight.
 *
 * <p>An inbound adapter parks the raw token here for the duration of the request, and
 * {@link IdentityHttpClient} reads it back when the application makes a call of its own — so
 * on-behalf-of propagation costs nothing beyond the HTTP client the application had to construct
 * anyway. An application does not assemble an identity header itself; it constructs that client and
 * makes ordinary calls.
 *
 * <p>The holder is a {@link ThreadLocal}, not a {@code ScopedValue}: this module targets Java 17, and
 * the servlet model this ships against runs a request on one thread. That does mean work handed to
 * another thread — an executor, a reactive scheduler — will not see the token, and must carry it
 * across explicitly via {@link #currentUserToken()}.
 *
 * <p>Requests with no verified caller — a cron trigger, a pub/sub delivery — produce no header at all
 * rather than an empty one, so a downstream service can tell "no user" from "a user with a blank
 * token".
 */
public final class IdentityContext {

  /** The header the whole Diagrid platform carries end-user identity in, inbound and outbound. */
  public static final String USER_TOKEN_HEADER = "X-Diagrid-User-Token";

  /** The scheme prefix on the header value, including its trailing space. */
  public static final String BEARER_PREFIX = "Bearer ";

  private static final ThreadLocal<String> CURRENT_USER_TOKEN = new ThreadLocal<>();

  private IdentityContext() {
  }

  /**
   * Parks the caller's raw token for the rest of this request.
   *
   * <p>Always pair this with {@link #clearCurrentToken()} in a {@code finally} block: servlet threads
   * are pooled, and a token left behind would be attached to whichever unrelated request next lands
   * on the thread.
   *
   * @param rawToken the bearer token, with any {@code Bearer } prefix already stripped
   */
  public static void setCurrentToken(String rawToken) {
    CURRENT_USER_TOKEN.set(rawToken);
  }

  /** Forgets the current request's token. Safe to call when none was set. */
  public static void clearCurrentToken() {
    CURRENT_USER_TOKEN.remove();
  }

  /**
   * The raw token of the caller this thread is serving.
   *
   * @return the bearer token, or {@code null} outside an authenticated request
   */
  public static String currentUserToken() {
    return CURRENT_USER_TOKEN.get();
  }

  /**
   * The headers carrying the caller's identity on an outbound call.
   *
   * <p>Package-private: the advertised outbound path is {@link IdentityHttpClient}, which also gets
   * the clearing and the origin guard right.
   *
   * @return a single-entry map, or an empty map when there is no inbound user context
   */
  static Map<String, String> outboundIdentityHeaders() {
    String token = CURRENT_USER_TOKEN.get();
    if (token == null || token.isEmpty()) {
      return Map.of();
    }
    return Map.of(USER_TOKEN_HEADER, BEARER_PREFIX + token);
  }

  /**
   * Strips the scheme prefix from a raw header value.
   *
   * <p>The prefix is matched case-insensitively, because clients spell it every way there is, and
   * the result is trimmed so a header of only whitespace reads as absent.
   *
   * @param headerValue the raw {@code X-Diagrid-User-Token} value, possibly {@code null}
   * @return the bare token, or an empty string when the header carries none
   */
  public static String trimBearer(String headerValue) {
    if (headerValue == null) {
      return "";
    }
    String value = headerValue.trim();
    if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      value = value.substring(BEARER_PREFIX.length());
    }
    return value.trim();
  }
}
