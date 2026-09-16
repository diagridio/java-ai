package io.diagrid.ai.identity;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The client {@link IdentityHttpClient} hands back: a {@link HttpClient} that stamps the calling
 * user's identity on every request it sends.
 *
 * <p>Package-private on purpose. The factory's return type is {@code HttpClient}, so an application
 * never names this class and the identity behaviour is not a type it can accidentally depend on.
 *
 * <p>Redirects are followed here rather than by the client underneath, which is built with
 * {@link HttpClient.Redirect#NEVER}. The JDK follows redirects below any interception point, and the
 * identity header has to be reconsidered on each hop, so the guard that keeps the caller's token on
 * the origin the caller addressed could not otherwise fire at all. The walk also takes on what the
 * JDK's redirect filter did: the header rules in {@link OutboundRedirects}, and the hop budget here.
 */
final class IdentityAwareHttpClient extends HttpClient {

  private static final Logger LOGGER = System.getLogger(IdentityAwareHttpClient.class.getName());

  /** Hops followed before the exchange is failed rather than continued. Mirrors the JDK's limit. */
  private static final int MAX_REDIRECTS = 5;

  /**
   * Refusing the hop past the budget is an {@link IOException}, as in the JDK: handing the caller
   * the 3xx instead would read as a successful exchange whose body is a redirect page.
   */
  private static final String TOO_MANY_REDIRECTS = "too many redirects";

  private final HttpClient delegate;
  private final Redirect redirects;
  private final boolean followsRedirects;

  /**
   * @param delegate the client that does the sending
   * @param redirects the redirect policy this client reports and applies
   * @param followsRedirects whether this client follows redirects itself, which it does only when
   *     {@code delegate} was built not to
   */
  IdentityAwareHttpClient(HttpClient delegate, Redirect redirects, boolean followsRedirects) {
    this.delegate = delegate;
    this.redirects = redirects;
    this.followsRedirects = followsRedirects;
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    URI origin = request.uri();
    Map<String, String> identity = identityForCall(request);
    HttpRequest hop = request;
    int hops = 0;
    while (true) {
      HttpResponse<T> response = delegate.send(withIdentity(hop, origin, identity), responseBodyHandler);
      Optional<HttpRequest> next = nextHop(hop, response, hops);
      if (next.isEmpty()) {
        return response;
      }
      hop = next.get();
      hops++;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler) {
    return sendAsync(request, responseBodyHandler, null);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> responseBodyHandler, PushPromiseHandler<T> pushPromiseHandler) {
    return sendHop(request.uri(), request, identityForCall(request), responseBodyHandler, pushPromiseHandler, 0);
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  /** The policy this client applies, which is the caller's rather than the delegate's. */
  @Override
  public Redirect followRedirects() {
    return redirects;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  /**
   * The identity to carry on this call, read on the calling thread at the moment the call is made.
   *
   * <p>Read once per call rather than once per hop: {@link IdentityContext} holds the token in a
   * {@link ThreadLocal}, which is not visible on the threads the client completes an async request
   * on, so a per-hop read would silently drop the identity on an asynchronous redirect. It is still
   * read when the application sends, never when the client is constructed, which is what makes one
   * shared client safe for concurrent callers.
   */
  private static Map<String, String> identityForCall(HttpRequest request) {
    Map<String, String> identity = IdentityContext.outboundIdentityHeaders();
    if (identity.isEmpty()) {
      LOGGER.log(Level.DEBUG, "no inbound user context; calling {0} unauthenticated", request.uri());
    }
    return identity;
  }

  /**
   * A copy of {@code request} carrying {@code identity}, if this hop is still allowed to see it.
   *
   * <p>The header is cleared first, so a request never carries an identity the current context does
   * not hold, whatever set it — an application header, or a previous hop of this same exchange.
   */
  private static HttpRequest withIdentity(HttpRequest request, URI origin, Map<String, String> identity) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request, (name, value) -> !IdentityContext.USER_TOKEN_HEADER.equalsIgnoreCase(name));
    if (identity.isEmpty()) {
      return builder.build();
    }
    if (!OutboundOrigin.sameOrigin(origin, request.uri())) {
      LOGGER.log(Level.DEBUG, "identity withheld: {0} is not the origin called", request.uri());
      return builder.build();
    }
    identity.forEach(builder::header);
    return builder.build();
  }

  private <T> CompletableFuture<HttpResponse<T>> sendHop(
      URI origin,
      HttpRequest request,
      Map<String, String> identity,
      BodyHandler<T> responseBodyHandler,
      PushPromiseHandler<T> pushPromiseHandler,
      int hops) {
    return delegate
        .sendAsync(withIdentity(request, origin, identity), responseBodyHandler, pushPromiseHandler)
        .thenCompose(response -> continueFrom(origin, request, response, identity, responseBodyHandler,
            pushPromiseHandler, hops));
  }

  /**
   * The rest of the exchange after {@code response}: the next hop, the answer, or the failure.
   *
   * <p>{@link #nextHop(HttpRequest, HttpResponse, int)} throws where the budget is spent, which a
   * {@code thenCompose} function cannot do, so the exception becomes a failed future here instead.
   */
  private <T> CompletableFuture<HttpResponse<T>> continueFrom(
      URI origin,
      HttpRequest request,
      HttpResponse<T> response,
      Map<String, String> identity,
      BodyHandler<T> responseBodyHandler,
      PushPromiseHandler<T> pushPromiseHandler,
      int hops) {
    Optional<HttpRequest> next;
    try {
      next = nextHop(request, response, hops);
    } catch (IOException e) {
      return CompletableFuture.failedFuture(e);
    }
    return next
        .map(hop -> sendHop(origin, hop, identity, responseBodyHandler, pushPromiseHandler, hops + 1))
        .orElseGet(() -> CompletableFuture.completedFuture(response));
  }

  /**
   * The next hop of this exchange, if there is one.
   *
   * @throws IOException when there is one but the hop budget is already spent
   */
  private Optional<HttpRequest> nextHop(HttpRequest request, HttpResponse<?> response, int hops)
      throws IOException {
    if (!followsRedirects) {
      return Optional.empty();
    }
    Optional<HttpRequest> next = OutboundRedirects.nextHop(request, response, redirects);
    if (next.isPresent() && hops >= MAX_REDIRECTS) {
      LOGGER.log(Level.DEBUG, "refusing hop {0} for {1}", hops + 1, request.uri());
      throw new IOException(TOO_MANY_REDIRECTS + " (" + MAX_REDIRECTS + ")");
    }
    return next;
  }
}
