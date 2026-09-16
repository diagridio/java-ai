package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the application's own headers do when a redirect leaves the origin.
 *
 * <p>The identity header has its own rule, tested in {@link IdentityHttpClientRedirectTest}. These
 * three the client has to drop itself, because it takes the redirect walk away from the JDK
 * precisely so the identity header can be reconsidered per hop.
 *
 * <p>Two servers on two loopback host spellings stand for two origins, so nothing here leaves the
 * loopback interface.
 */
class OutboundHeadersTest {

  private static final String CALLED_HOST = "127.0.0.1";
  private static final String OTHER_HOST = "localhost";
  private static final int OK = 200;
  private static final int FOUND = 302;
  private static final int SEE_OTHER = 303;

  private static final String AUTHORIZATION = "Authorization";
  private static final String COOKIE = "Cookie";
  private static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
  private static final String CONTENT_TYPE = "Content-Type";

  private static final String APP_CREDENTIAL = "Bearer the-apps-own-credential";
  private static final String APP_COOKIE = "session=abc123";

  @BeforeAll
  static void bothHostsAreLoopback() throws Exception {
    assumeTrue(
        InetAddress.getByName(OTHER_HOST).isLoopbackAddress(),
        "the test stays on loopback: " + OTHER_HOST + " must resolve there");
  }

  private static HttpClient following() {
    return IdentityHttpClient.from(HttpClient.newBuilder(), HttpClient.Redirect.ALWAYS);
  }

  /**
   * The two credential headers the JDK will actually put on the wire.
   *
   * <p>{@code Proxy-Authorization} is not one of them: the JDK drops it on a direct connection
   * whatever the request asks for, so it cannot be observed arriving anywhere and is covered by
   * {@link #stripListMatchesTheOtherSdks()} instead.
   */
  private static HttpRequest withCredentials(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header(AUTHORIZATION, APP_CREDENTIAL)
        .header(COOKIE, APP_COOKIE)
        .GET()
        .build();
  }

  @Test
  @DisplayName("the strip list is the same three names every other Diagrid SDK drops")
  void stripListMatchesTheOtherSdks() {
    assertEquals(
        Set.of(AUTHORIZATION.toLowerCase(Locale.ROOT), COOKIE.toLowerCase(Locale.ROOT),
            PROXY_AUTHORIZATION.toLowerCase(Locale.ROOT)),
        OutboundRedirects.CROSS_ORIGIN_STRIPPED_HEADERS);
    assertFalse(
        OutboundRedirects.CROSS_ORIGIN_STRIPPED_HEADERS.contains(
            IdentityContext.USER_TOKEN_HEADER.toLowerCase(Locale.ROOT)),
        "the identity header has its own, stricter rule");
  }

  @Nested
  @DisplayName("a redirect that leaves the origin")
  class CrossOrigin {

    @Test
    @DisplayName("carries none of Authorization, Cookie or Proxy-Authorization")
    void stripsTheApplicationsOwnCredentials() throws Exception {
      try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST);
          RecordingHttpServer elsewhere = RecordingHttpServer.start(OTHER_HOST)) {
        elsewhere.ok("/elsewhere", "elsewhere");
        called.redirect("/cross", elsewhere.uri("/elsewhere").toString());

        HttpResponse<String> response = following()
            .send(withCredentials(called.uri("/cross")), HttpResponse.BodyHandlers.ofString());

        assertEquals(OK, response.statusCode());
        assertTrue(elsewhere.wasCalled("/elsewhere"), "the redirect was followed");
        assertEquals(List.of(APP_CREDENTIAL), called.header("/cross", AUTHORIZATION));
        assertEquals(List.of(APP_COOKIE), called.header("/cross", COOKIE));
        assertEquals(List.of(), elsewhere.header("/elsewhere", AUTHORIZATION));
        assertEquals(List.of(), elsewhere.header("/elsewhere", COOKIE));
      }
    }

    @Test
    @DisplayName("strips them on the async path too")
    void stripsTheApplicationsOwnCredentialsAsync() throws Exception {
      try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST);
          RecordingHttpServer elsewhere = RecordingHttpServer.start(OTHER_HOST)) {
        elsewhere.ok("/elsewhere", "elsewhere");
        called.redirect("/cross", elsewhere.uri("/elsewhere").toString());

        HttpResponse<String> response = following()
            .sendAsync(withCredentials(called.uri("/cross")), HttpResponse.BodyHandlers.ofString())
            .get();

        assertEquals(OK, response.statusCode());
        assertEquals(List.of(), elsewhere.header("/elsewhere", AUTHORIZATION));
        assertEquals(List.of(), elsewhere.header("/elsewhere", COOKIE));
      }
    }
  }

  @Test
  @DisplayName("a redirect within the origin keeps the application's own credentials")
  void keepsThemOnASameOriginRedirect() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST)) {
      called.redirect("/hop", "/landed").ok("/landed", "landed");

      HttpResponse<String> response = following()
          .send(withCredentials(called.uri("/hop")), HttpResponse.BodyHandlers.ofString());

      assertEquals(OK, response.statusCode());
      assertEquals(List.of(APP_CREDENTIAL), called.header("/landed", AUTHORIZATION));
      assertEquals(List.of(APP_COOKIE), called.header("/landed", COOKIE));
    }
  }

  @Test
  @DisplayName("a 303 that turns a POST into a GET does not describe a body it no longer has")
  void dropsBodyHeadersWhenTheMethodChanges() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST)) {
      called.redirect("/submit", SEE_OTHER, "/result").ok("/result", "result");
      HttpRequest post = HttpRequest.newBuilder(called.uri("/submit"))
          .header(CONTENT_TYPE, "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build();

      HttpResponse<String> response = following().send(post, HttpResponse.BodyHandlers.ofString());

      assertEquals(OK, response.statusCode());
      assertEquals(List.of("application/json"), called.header("/submit", CONTENT_TYPE));
      assertEquals(List.of(), called.header("/result", CONTENT_TYPE));
    }
  }

  @Nested
  @DisplayName("exhausting the redirect budget")
  class RedirectBudget {

    /** One more hop than the client will take, so the budget is certainly spent. */
    private static final int HOPS = 8;

    private RecordingHttpServer chainOf(int hops) throws IOException {
      RecordingHttpServer server = RecordingHttpServer.start(CALLED_HOST);
      for (int hop = 0; hop < hops; hop++) {
        server.redirect("/hop" + hop, FOUND, "/hop" + (hop + 1));
      }
      return server.ok("/hop" + hops, "landed");
    }

    @Test
    @DisplayName("is an IOException, not a 3xx handed back as the answer")
    void raisesRatherThanReturningTheRedirect() throws Exception {
      try (RecordingHttpServer called = chainOf(HOPS)) {
        HttpClient client = following();
        HttpRequest request = HttpRequest.newBuilder(called.uri("/hop0")).GET().build();

        IOException thrown = assertThrows(
            IOException.class,
            () -> client.send(request, HttpResponse.BodyHandlers.ofString()));

        assertTrue(thrown.getMessage().contains("too many redirects"), thrown.getMessage());
      }
    }

    @Test
    @DisplayName("fails the future on the async path")
    void failsTheFutureOnTheAsyncPath() throws Exception {
      try (RecordingHttpServer called = chainOf(HOPS)) {
        HttpRequest request = HttpRequest.newBuilder(called.uri("/hop0")).GET().build();

        ExecutionException thrown = assertThrows(
            ExecutionException.class,
            () -> following()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get());

        assertEquals(IOException.class, thrown.getCause().getClass());
      }
    }

    @Test
    @DisplayName("a chain that ends inside the budget still answers")
    void aChainInsideTheBudgetIsFollowed() throws Exception {
      try (RecordingHttpServer called = chainOf(2)) {
        HttpResponse<String> response = following().send(
            HttpRequest.newBuilder(called.uri("/hop0")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(OK, response.statusCode());
        assertEquals("landed", response.body());
      }
    }
  }
}
