package io.diagrid.ai.identity;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * HTTP clients that carry the calling user's identity on the calls an app makes next.
 *
 * <p>The sidecar mints an on-behalf-of token for whoever it identified on the <em>inbound</em>
 * request. An outbound call to the MCP proxy or a sub-agent is a separate, stateless request, so
 * that token has to ride on it explicitly. Construct one of these clients and make ordinary calls;
 * the app never assembles an identity header itself:
 *
 * <pre>{@code
 * private final HttpClient http =
 *     IdentityHttpClient.from(HttpClient.newBuilder(), HttpClient.Redirect.NORMAL);
 *
 * HttpResponse<String> response =
 *     http.send(HttpRequest.newBuilder(mcpUri).build(), BodyHandlers.ofString());
 * }</pre>
 *
 * <p>What comes back is a {@link HttpClient}, not a new type, so it goes wherever one is expected —
 * an MCP client, a generated API client, a Spring {@code JdkClientHttpRequestFactory}.
 *
 * <p>The token is read from {@link IdentityContext} at <em>send</em> time, not baked in at
 * construction. That is what makes one long-lived, shared client safe: concurrent requests each
 * carry their own caller's token, where a token captured at construction would send whichever user
 * happened to be current when the client was built.
 *
 * <p>The token only ever goes to the origin the caller addressed: a redirect to a different origin
 * drops the header, since without that a redirect from the callee would hand the caller's token to
 * whatever host the redirect names. Past that the client is as wide as you make it, so call
 * third-party APIs with a plain {@code HttpClient} instead.
 *
 * <p>A request made with no inbound user — a cron trigger, a pub/sub delivery — carries no identity
 * header at all rather than an empty one, and is not an error: the call goes out unauthenticated
 * and the omission is logged at {@link Level#DEBUG}.
 *
 * <p>Two limitations worth knowing. {@code X-Diagrid-User-Token} is not a header name log scrubbers
 * and tracing SDKs redact by default. And on Java 21+ {@code close()} on one of these clients does
 * not close the client underneath it, so keep the one long-lived instance these are designed for
 * rather than a client per call.
 */
public final class IdentityHttpClient {

  private static final Logger LOGGER = System.getLogger(IdentityHttpClient.class.getName());

  private IdentityHttpClient() {
  }

  /**
   * An identity-aware client on the JDK defaults, which do not follow redirects.
   *
   * @return a {@link HttpClient} that carries the calling user's identity
   */
  public static HttpClient newHttpClient() {
    return from(HttpClient.newBuilder(), HttpClient.Redirect.NEVER);
  }

  /**
   * An identity-aware client on the options of {@code builder}.
   *
   * <p>Every option {@link HttpClient.Builder} takes is honoured — timeouts, proxy, executor, SSL,
   * cookies, authenticator — with one exception: redirect following belongs to the returned client
   * rather than to the client underneath it, so that a hop leaving the origin can be stripped of the
   * caller's identity before it goes out. State it through {@code redirects}; the value set on
   * {@code builder} is replaced with {@link HttpClient.Redirect#NEVER}, which is also the only
   * option of the caller's this method modifies.
   *
   * @param builder the options the client is built from
   * @param redirects whether the returned client follows redirects, with the meaning
   *     {@link HttpClient.Builder#followRedirects(HttpClient.Redirect)} gives each value
   * @return a {@link HttpClient} that carries the calling user's identity
   */
  public static HttpClient from(HttpClient.Builder builder, HttpClient.Redirect redirects) {
    Objects.requireNonNull(builder, "builder");
    Objects.requireNonNull(redirects, "redirects");
    HttpClient delegate = builder.followRedirects(HttpClient.Redirect.NEVER).build();
    return new IdentityAwareHttpClient(delegate, redirects, true);
  }

  /**
   * Identity on a client the app already owns and cannot replace.
   *
   * <p>Everything about {@code client} is kept — its configuration, its connection pool, whatever
   * authenticator or cookie handler it was built with — and identity is applied on top, so it wins
   * over anything that set the same header.
   *
   * <p>The origin guard cannot be enforced when {@code client} follows redirects itself, since the
   * JDK follows them below any wrapper: a redirect would carry the caller's token to the host it
   * names. A client built with {@link HttpClient.Redirect#NEVER} — the JDK default — has no such
   * hop; anything else warns here and should be built with
   * {@link #from(HttpClient.Builder, HttpClient.Redirect)} instead.
   *
   * @param client the client to apply identity to
   * @return a {@link HttpClient} delegating to {@code client} with the caller's identity attached
   */
  public static HttpClient wrap(HttpClient client) {
    Objects.requireNonNull(client, "client");
    HttpClient.Redirect redirects = client.followRedirects();
    if (redirects != HttpClient.Redirect.NEVER) {
      LOGGER.log(
          Level.WARNING,
          "wrapped client follows redirects itself ({0}): a redirect away from the origin called will"
              + " carry the caller''s identity. Build it with IdentityHttpClient.from instead.",
          redirects);
    }
    return new IdentityAwareHttpClient(client, redirects, false);
  }
}
