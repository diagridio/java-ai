package io.diagrid.springai.identity;

import io.diagrid.ai.identity.IdentityContext;
import io.diagrid.ai.identity.IdentityHttpClient;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Carries the calling user's identity on the calls a {@code RestClient} or {@code RestTemplate}
 * makes, for an application that already owns one and cannot replace it.
 *
 * <pre>{@code
 * RestClient client = RestClient.builder()
 *     .requestInterceptor(new IdentityClientHttpRequestInterceptor())
 *     .build();
 * }</pre>
 *
 * <p>Install it last, after the application's own interceptors, so identity wins over anything that
 * set the same header. {@code RestClient.Builder.requestInterceptor} appends, so the last one
 * registered is the last one to run.
 *
 * <p>The token is read at exchange time, not when the interceptor is constructed, so one interceptor
 * on one shared client stays correct for concurrent callers. The header is cleared first, so a
 * request never carries an identity the current context does not hold; a request with no inbound
 * caller goes out with no identity header at all, which is not an error.
 *
 * <p>One property of {@link IdentityHttpClient} does not reach this path: the caller's token is
 * pinned to the origin the caller addressed, and redirects are followed by the request factory
 * underneath an interceptor, where a Spring interceptor cannot see them. An application that can
 * choose its request factory gets the guard back by building the factory over
 * {@link IdentityHttpClient}:
 *
 * <pre>{@code
 * RestClient.builder()
 *     .requestFactory(new JdkClientHttpRequestFactory(
 *         IdentityHttpClient.from(HttpClient.newBuilder(), HttpClient.Redirect.NORMAL)))
 *     .build();
 * }</pre>
 *
 * <p>Installing this interceptor says that at {@code WARNING} once per process. Nothing about the
 * request changes, and an application that has made its choice can silence the line by level or by
 * logger name.
 */
public final class IdentityClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger LOGGER =
      System.getLogger(IdentityClientHttpRequestInterceptor.class.getName());

  private static final String REDIRECT_UNGUARDED_WARNING =
      "IdentityClientHttpRequestInterceptor carries the caller's identity but cannot pin it to the "
          + "origin the caller addressed: a Spring request factory follows redirects underneath an "
          + "interceptor, where this cannot see them, so the header can ride to a redirect target "
          + "of the callee's choosing. Build the request factory over IdentityHttpClient, which "
          + "re-decides the header on every hop, when the application can choose its factory. "
          + "Warned once per process.";

  /**
   * Whether the warning above has already been said.
   *
   * <p>Once per process rather than once per interceptor: an application with several clients would
   * otherwise get the same paragraph on every one, and a repeated warning is a line an operator
   * filters out. Package-private so a test can have it said again.
   */
  static final AtomicBoolean WARNED = new AtomicBoolean();

  /**
   * Installs the interceptor, warning once per process that the origin guard cannot reach this path.
   */
  public IdentityClientHttpRequestInterceptor() {
    if (WARNED.compareAndSet(false, true)) {
      LOGGER.log(Level.WARNING, REDIRECT_UNGUARDED_WARNING);
    }
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    HttpHeaders headers = request.getHeaders();
    headers.remove(IdentityContext.USER_TOKEN_HEADER);
    String token = IdentityContext.currentUserToken();
    if (token == null || token.isEmpty()) {
      LOGGER.log(Level.DEBUG, "no inbound user context; calling {0} unauthenticated", request.getURI());
    } else {
      headers.set(IdentityContext.USER_TOKEN_HEADER, IdentityContext.BEARER_PREFIX + token);
    }
    return execution.execute(request, body);
  }
}
