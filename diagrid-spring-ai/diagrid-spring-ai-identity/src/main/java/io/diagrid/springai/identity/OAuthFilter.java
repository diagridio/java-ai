package io.diagrid.springai.identity;

import io.diagrid.ai.identity.IdentityContext;
import io.diagrid.ai.identity.IdentityNotConfiguredException;
import io.diagrid.ai.identity.JwksVerifier;
import io.diagrid.ai.identity.OAuthConfig;
import io.diagrid.ai.identity.OAuthErrorBody;
import io.diagrid.ai.identity.OAuthErrorCodes;
import io.diagrid.ai.identity.TokenVerificationException;
import io.diagrid.ai.identity.TokenVerifier;
import io.diagrid.ai.identity.VerifiedUser;
import io.diagrid.ai.identity.VerifierNotReadyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the {@code X-Diagrid-User-Token} header on every inbound request.
 *
 * <p>One bean buys verified inbound identity, and outbound on-behalf-of propagation then needs no
 * plumbing of its own on a request served by one thread:
 *
 * <pre>{@code
 * @Bean
 * OAuthFilter diagridOAuthFilter() {
 *   return new OAuthFilter(new OAuthConfig(Set.of("agent.invoke")));
 * }
 * }</pre>
 *
 * <p>Handlers read the caller with {@link #verifiedUser(HttpServletRequest)}:
 *
 * <pre>{@code
 * VerifiedUser user = OAuthFilter.verifiedUser(request).orElseThrow();
 * }</pre>
 *
 * <p>Outbound calls carry that same caller when they are made with
 * {@link io.diagrid.ai.identity.IdentityHttpClient}, or with
 * {@link IdentityClientHttpRequestInterceptor} on a {@code RestClient} the application already owns.
 * Neither asks the application to assemble an identity header.
 *
 * <p><strong>Asynchronous handlers.</strong> The token {@link IdentityContext} hands to outbound
 * calls is a {@link ThreadLocal}, so it is visible only on the thread the filter runs on. The filter
 * runs on the async dispatch too (see {@link #shouldNotFilterAsyncDispatch()}), so work resuming
 * there carries the caller; a thread the handler hands work to itself — an executor, a reactive
 * scheduler, a {@code ChatClient.stream()} {@code Flux} — does not. Those must read
 * {@link IdentityContext#currentUserToken()} on the request thread and set it again on the other
 * side. Skipping that is silent: the outbound call simply goes out with no identity header.
 *
 * <p>This is deliberately not built on Spring Security, whose authentication entry point renders
 * its own response: the status codes and the {@code {"error":"oauth.…"}} body are a contract shared
 * with every other Diagrid SDK.
 *
 * <p><strong>No request leaves here as a 500.</strong> Every failure this filter can reach —
 * including one no version of this code anticipated — is answered with the same
 * {@code {"error":"oauth.…"}} envelope and {@code Cache-Control: no-store}, because a caller that
 * cannot branch on a code has nothing to retry or re-authenticate against. An unanticipated failure
 * of the verifier or of its construction is reported as 503 {@code oauth.verifier_unavailable};
 * 503 {@code oauth.not_configured} is reserved for coordinates that could not be resolved.
 *
 * <p>Everything the filter decides comes from {@code diagrid-ai-identity}, which has no Spring on
 * its classpath: this class reads the header, calls out, and turns the answer into HTTP. Keep it
 * that way, so another framework's adapter can be written against the same behaviour.
 */
public class OAuthFilter extends OncePerRequestFilter {

  /** Request attribute the verified caller is published under. */
  public static final String USER_ATTRIBUTE = "diagrid.user";

  /**
   * The caller this filter verified for the given request.
   *
   * <p>Empty means no verified caller is attached — a route the policy admits unauthenticated
   * ({@code requireAuth = false}), or a request this filter never ran on — and empty rather than a
   * {@code ClassCastException} if something else has taken the attribute over.
   *
   * @param request the request being served
   * @return the verified caller, or empty when the request carries none
   */
  public static Optional<VerifiedUser> verifiedUser(HttpServletRequest request) {
    Object attribute = request == null ? null : request.getAttribute(USER_ATTRIBUTE);
    return attribute instanceof VerifiedUser user ? Optional.of(user) : Optional.empty();
  }

  /**
   * How long a failed verifier build is remembered before discovery is attempted again.
   *
   * <p>Discovery is a blocking HTTP call to the sidecar plus a synchronous JWKS fetch, on the
   * request thread. Without this window a sidecar that is reachable but hung would cost every
   * request the full metadata timeout and hold the container's thread pool there.
   */
  static final Duration VERIFIER_RETRY_BACKOFF = Duration.ofSeconds(30);

  private static final String JSON_CONTENT_TYPE = "application/json";

  /** An authorization failure must never be cached — the next caller is a different principal. */
  private static final String CACHE_CONTROL_HEADER = "Cache-Control";

  private static final String NO_STORE = "no-store";

  private static final Logger LOGGER = System.getLogger(OAuthFilter.class.getName());

  private final OAuthConfig config;

  /**
   * The verifier is built on the first request that carries a token, not in the constructor, so an
   * app whose sidecar is not up yet still starts. Volatile rather than a lock: two concurrent first
   * requests may each build one, and the loser's is simply discarded.
   */
  private volatile TokenVerifier verifier;

  /**
   * The last failed attempt to build one, so the next request does not repeat it immediately. Held
   * as one object so the instant and its cause are read together and cannot disagree.
   */
  private volatile VerifierFailure lastFailure;

  /**
   * Enforces the given policy, discovering the issuer from the sidecar on first use.
   *
   * @param config the policy to enforce; {@code null} means "any verified caller"
   */
  public OAuthFilter(OAuthConfig config) {
    this.config = config == null ? new OAuthConfig() : config;
  }

  /**
   * Enforces the given policy against a verifier you supply.
   *
   * <p>For tests, and for apps that resolve identity coordinates themselves.
   *
   * @param config   the policy to enforce; {@code null} means "any verified caller"
   * @param verifier the verifier to check tokens with
   */
  public OAuthFilter(OAuthConfig config, TokenVerifier verifier) {
    this(config);
    this.verifier = verifier;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Only this header is honoured: Authorization belongs to whatever the app itself authenticates
    // with, and reading it as a user token would let a caller's own credential impersonate a user.
    String token = IdentityContext.trimBearer(request.getHeader(IdentityContext.USER_TOKEN_HEADER));

    if (token.isEmpty()) {
      if (config.requireAuth()) {
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, OAuthErrorCodes.MISSING_TOKEN);
        return;
      }
      IdentityContext.clearCurrentToken();
      chain.doFilter(request, response);
      return;
    }

    Map<String, Object> claims;
    try {
      claims = verifier().verify(token);
    } catch (IdentityNotConfiguredException e) {
      // Refused rather than served unauthenticated: a misconfigured deployment must be visible.
      LOGGER.log(Level.WARNING, "identity verifier not configured; rejecting request", e);
      reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, OAuthErrorCodes.NOT_CONFIGURED);
      return;
    } catch (VerifierNotReadyException e) {
      reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, OAuthErrorCodes.VERIFIER_UNAVAILABLE);
      return;
    } catch (TokenVerificationException e) {
      reject(response, statusFor(e.code()), e.code());
      return;
    } catch (RuntimeException e) {
      // Ordered last so the three typed failures above keep their own codes: a broad catch ahead of
      // them would turn a correctly rejected token into a 503. Anything that reaches here means the
      // app cannot adjudicate any caller right now, so it is reported as the verifier being
      // unavailable rather than as a 401 the presented token did not earn. Catching
      // RuntimeException and not Throwable leaves an Error, and a client abort arriving as an
      // IOException, to the container that needs to see them.
      LOGGER.log(Level.WARNING, "identity verification failed unexpectedly; rejecting request", e);
      reject(
          response,
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          OAuthErrorCodes.VERIFIER_UNAVAILABLE);
      return;
    }

    VerifiedUser user = VerifiedUser.fromClaims(claims);
    Set<String> missing = config.missingScopes(user.scopes());
    if (!missing.isEmpty()) {
      reject(response, HttpServletResponse.SC_FORBIDDEN, OAuthErrorCodes.MISSING_SCOPE);
      return;
    }

    request.setAttribute(USER_ATTRIBUTE, user);
    IdentityContext.setCurrentToken(token);
    try {
      chain.doFilter(request, response);
    } finally {
      // Servlet threads are pooled: leaving the token set would attach this caller's identity to
      // whichever unrelated request lands on the thread next.
      IdentityContext.clearCurrentToken();
    }
  }

  private TokenVerifier verifier() {
    TokenVerifier current = verifier;
    if (current != null) {
      return current;
    }

    VerifierFailure failure = lastFailure;
    if (failure != null && now().isBefore(failure.at().plus(VERIFIER_RETRY_BACKOFF))) {
      // The same exception instance is rethrown, so a caller sees one stable error code for as
      // long as the window is open rather than one that changes with how a retry happened to fail.
      throw failure.cause();
    }

    try {
      current = createVerifier();
    } catch (RuntimeException e) {
      lastFailure = new VerifierFailure(now(), e);
      // DEBUG, not WARNING: the rejection this failure causes logs its own warning, and the
      // backoff window is this adapter's detail.
      LOGGER.log(
          Level.DEBUG,
          "identity discovery failed; not retrying for " + VERIFIER_RETRY_BACKOFF.toSeconds() + "s",
          e);
      throw e;
    }
    lastFailure = null;
    verifier = current;
    return current;
  }

  /**
   * Builds the verifier, which is where discovery happens.
   *
   * <p>Package-private and overridable purely as a test seam: a test counts how often discovery is
   * attempted. Production code configures this through {@link OAuthConfig}.
   *
   * @return a warmed verifier for the configured policy
   */
  TokenVerifier createVerifier() {
    return JwksVerifier.build(config);
  }

  /**
   * The clock the retry backoff is measured against.
   *
   * <p>Package-private and overridable purely as a test seam, so a test can move past the backoff
   * window without sleeping.
   *
   * @return the current instant
   */
  Instant now() {
    return Instant.now();
  }

  /**
   * Runs on the async dispatch too, rather than only on the initial one.
   *
   * <p>{@link OncePerRequestFilter} skips async dispatches by default, and that default is wrong
   * here. A streaming handler returns from {@code chain.doFilter} immediately, which runs the
   * {@code finally} that clears the token, while its outbound MCP and sub-agent calls happen after
   * that point — so with the default an identity-aware client would quietly send them
   * unauthenticated.
   *
   * @return {@code false}, always
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  private static int statusFor(String code) {
    return OAuthErrorCodes.MISSING_SCOPE.equals(code)
        ? HttpServletResponse.SC_FORBIDDEN
        : HttpServletResponse.SC_UNAUTHORIZED;
  }

  private static void reject(HttpServletResponse response, int status, String code) throws IOException {
    if (response.isCommitted()) {
      // The handler is already streaming: the status line is gone and appending a JSON error would
      // corrupt the body the caller is reading. Refusing to continue is still the answer; there is
      // just nowhere left to say so.
      LOGGER.log(Level.WARNING, "cannot report {0}: the response is already committed", code);
      return;
    }
    response.setStatus(status);
    response.setContentType(JSON_CONTENT_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(CACHE_CONTROL_HEADER, NO_STORE);
    response.getWriter().write(new OAuthErrorBody(code).toJson());
  }

  /** A failed verifier build, remembered for {@link #VERIFIER_RETRY_BACKOFF}. */
  private record VerifierFailure(Instant at, RuntimeException cause) {
  }
}
