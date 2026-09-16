package io.diagrid.springai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.ai.identity.IdentityContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class IdentityClientHttpRequestInterceptorTest {

  private static final URI DOWNSTREAM = URI.create("http://downstream.test/mcp");

  private final IdentityClientHttpRequestInterceptor interceptor = new IdentityClientHttpRequestInterceptor();

  @AfterEach
  void clearToken() {
    IdentityContext.clearCurrentToken();
  }

  @Test
  @DisplayName("sets the caller's identity on a client the app already owns")
  void setsTheIdentityHeader() throws Exception {
    IdentityContext.setCurrentToken("tok");

    assertEquals("Bearer tok", intercept(null));
  }

  @Test
  @DisplayName("clears an identity header the request already carried")
  void clearsAStaleIdentityHeader() throws Exception {
    assertNull(intercept("Bearer someone-else"), "a header the current context does not hold");
  }

  @Test
  @DisplayName("omits the header entirely when there is no inbound caller")
  void omitsTheHeaderWithoutAnInboundCaller() throws Exception {
    assertNull(intercept(null));
  }

  /**
   * The limitation this path cannot engineer away, said out loud.
   *
   * <p>Installed on a {@code RestTemplate} or {@code RestClient} the interceptor sees one request
   * and nothing else: the request factory underneath follows redirects below that point, so the
   * per-hop origin guard {@link io.diagrid.ai.identity.IdentityHttpClient} enforces cannot be
   * reconstructed from here. Hence the warning, whose shape is what these cases pin.
   */
  @Nested
  @DisplayName("the unguarded-redirect warning")
  class RedirectWarning {

    @Test
    @DisplayName("names the limitation, once per process however many interceptors are installed")
    void warnsOncePerProcess() {
      List<LogRecord> warnings = capturingWarnings(() -> {
        new IdentityClientHttpRequestInterceptor();
        new IdentityClientHttpRequestInterceptor();
        new IdentityClientHttpRequestInterceptor();
      });

      assertEquals(1, warnings.size(), "a line per installed interceptor is noise an operator filters out");
      String message = warnings.get(0).getMessage();
      assertTrue(message.contains("redirect"), message);
      assertTrue(message.contains("IdentityHttpClient"), "the warning must name the way out: " + message);
    }

    /**
     * Runs {@code installation} with the once-per-process flag freshly cleared, collecting whatever
     * the interceptor's logger publishes at {@code WARNING} or above.
     */
    private List<LogRecord> capturingWarnings(Runnable installation) {
      List<LogRecord> seen = new ArrayList<>();
      Handler collector = new Handler() {
        @Override
        public void publish(LogRecord record) {
          seen.add(record);
        }

        @Override
        public void flush() {
          // Nothing is buffered.
        }

        @Override
        public void close() {
          // Nothing is held open.
        }
      };
      collector.setLevel(java.util.logging.Level.WARNING);
      java.util.logging.Logger logger =
          java.util.logging.Logger.getLogger(IdentityClientHttpRequestInterceptor.class.getName());
      logger.addHandler(collector);
      IdentityClientHttpRequestInterceptor.WARNED.set(false);
      try {
        installation.run();
      } finally {
        logger.removeHandler(collector);
        // Left as already-said: the flag is process-wide, and another test that happens to build an
        // interceptor should see the steady state a long-running app sees, not a fresh warning.
        IdentityClientHttpRequestInterceptor.WARNED.set(true);
      }
      return seen;
    }
  }

  /** Runs the interceptor over a request optionally pre-loaded with {@code presetIdentity}. */
  private String intercept(String presetIdentity) throws Exception {
    MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, DOWNSTREAM);
    if (presetIdentity != null) {
      request.getHeaders().set(IdentityContext.USER_TOKEN_HEADER, presetIdentity);
    }
    AtomicReference<String> sent = new AtomicReference<>();
    ClientHttpRequestExecution execution = (executed, body) -> {
      sent.set(executed.getHeaders().getFirst(IdentityContext.USER_TOKEN_HEADER));
      return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    };

    interceptor.intercept(request, new byte[0], execution);

    return sent.get();
  }
}
