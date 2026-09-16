package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentityHttpClientTest {

  private static final String LOOPBACK = "127.0.0.1";
  private static final int OK = 200;

  @AfterEach
  void clearToken() {
    IdentityContext.clearCurrentToken();
  }

  @Test
  @DisplayName("one client, two callers: each request carries its own caller's token")
  void readsTheTokenAtSendTimeNotConstructionTime() throws Exception {
    try (RecordingHttpServer server = RecordingHttpServer.start(LOOPBACK)) {
      server.ok("/alice", "alice").ok("/bob", "bob");
      // Built before either caller exists, and shared by both: a token captured here would be
      // whichever user happened to be current when the client was constructed.
      HttpClient client = IdentityHttpClient.newHttpClient();
      CountDownLatch bothReady = new CountDownLatch(1);
      ExecutorService callers = Executors.newFixedThreadPool(2);
      try {
        Future<Integer> alice = callers.submit(callAs(client, "alice-token", server.uri("/alice"), bothReady));
        Future<Integer> bob = callers.submit(callAs(client, "bob-token", server.uri("/bob"), bothReady));
        bothReady.countDown();

        assertEquals(OK, alice.get());
        assertEquals(OK, bob.get());
      } finally {
        callers.shutdownNow();
      }

      assertEquals(List.of("Bearer alice-token"), server.identityHeader("/alice"));
      assertEquals(List.of("Bearer bob-token"), server.identityHeader("/bob"));
    }
  }

  @Test
  @DisplayName("no inbound context: the header is absent, not empty, and the call still goes out")
  void omitsTheHeaderWithoutAnInboundCaller() throws Exception {
    try (RecordingHttpServer server = RecordingHttpServer.start(LOOPBACK)) {
      server.ok("/anon", "anon");
      HttpClient client = IdentityHttpClient.newHttpClient();

      HttpResponse<String> response = client.send(get(server.uri("/anon")), HttpResponse.BodyHandlers.ofString());

      assertEquals(OK, response.statusCode());
      assertTrue(server.wasCalled("/anon"), "a call with no caller still goes out");
      assertEquals(List.of(), server.identityHeader("/anon"));
    }
  }

  @Test
  @DisplayName("an identity header already on the request is cleared first")
  void clearsAnIdentityTheContextDoesNotHold() throws Exception {
    try (RecordingHttpServer server = RecordingHttpServer.start(LOOPBACK)) {
      server.ok("/stale", "stale").ok("/overridden", "overridden");
      HttpClient client = IdentityHttpClient.newHttpClient();

      client.send(
          preset(server.uri("/stale"), "Bearer someone-else"), HttpResponse.BodyHandlers.ofString());
      IdentityContext.setCurrentToken("the-caller");
      client.send(
          preset(server.uri("/overridden"), "Bearer someone-else"), HttpResponse.BodyHandlers.ofString());

      assertEquals(List.of(), server.identityHeader("/stale"));
      assertEquals(List.of("Bearer the-caller"), server.identityHeader("/overridden"));
    }
  }

  @Test
  @DisplayName("sendAsync carries the caller's identity too")
  void carriesIdentityOnTheAsyncPath() throws Exception {
    try (RecordingHttpServer server = RecordingHttpServer.start(LOOPBACK)) {
      server.ok("/async", "async");
      HttpClient client = IdentityHttpClient.newHttpClient();
      IdentityContext.setCurrentToken("async-token");

      HttpResponse<String> response =
          client.sendAsync(get(server.uri("/async")), HttpResponse.BodyHandlers.ofString()).get();

      assertEquals(OK, response.statusCode());
      assertEquals(List.of("Bearer async-token"), server.identityHeader("/async"));
    }
  }

  @Test
  @DisplayName("every option set on the builder survives, and the client is an ordinary HttpClient")
  void keepsCallerSuppliedOptions() {
    Duration connectTimeout = Duration.ofSeconds(7);
    HttpClient client =
        IdentityHttpClient.from(
            HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1),
            HttpClient.Redirect.NORMAL);

    assertEquals(Optional.of(connectTimeout), client.connectTimeout());
    assertEquals(HttpClient.Version.HTTP_1_1, client.version());
    assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
    assertSame(client, takesAnHttpClient(client), "usable wherever an HttpClient is expected");
  }

  @Test
  @DisplayName("wrapping a client the app already owns keeps that client's configuration")
  void wrapsAClientTheAppAlreadyOwns() throws Exception {
    try (RecordingHttpServer server = RecordingHttpServer.start(LOOPBACK)) {
      server.ok("/wrapped", "wrapped");
      HttpClient owned = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      HttpClient client = IdentityHttpClient.wrap(owned);
      IdentityContext.setCurrentToken("wrapped-token");

      HttpResponse<String> response = client.send(get(server.uri("/wrapped")), HttpResponse.BodyHandlers.ofString());

      assertEquals(OK, response.statusCode());
      assertEquals(List.of("Bearer wrapped-token"), server.identityHeader("/wrapped"));
      assertEquals(Optional.of(Duration.ofSeconds(3)), client.connectTimeout());
      assertFalse(client.cookieHandler().isPresent());
    }
  }

  private static HttpClient takesAnHttpClient(HttpClient client) {
    return client;
  }

  private static HttpRequest get(URI uri) {
    return HttpRequest.newBuilder(uri).GET().build();
  }

  private static HttpRequest preset(URI uri, String identityHeader) {
    return HttpRequest.newBuilder(uri).header(IdentityContext.USER_TOKEN_HEADER, identityHeader).GET().build();
  }

  private static Callable<Integer> callAs(HttpClient client, String token, URI uri, CountDownLatch ready) {
    return () -> {
      IdentityContext.setCurrentToken(token);
      try {
        ready.await();
        return send(client, uri);
      } finally {
        IdentityContext.clearCurrentToken();
      }
    };
  }

  private static int send(HttpClient client, URI uri) throws IOException, InterruptedException {
    return client.send(get(uri), HttpResponse.BodyHandlers.ofString()).statusCode();
  }
}
