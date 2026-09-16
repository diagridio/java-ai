package io.diagrid.ai.identity;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback HTTP server that records every header each request arrived with.
 *
 * <p>Addressed by the host spelling it was started with, so two servers can stand for two origins
 * without leaving loopback: the redirect cases need a "different host" the client can still reach.
 */
final class RecordingHttpServer implements AutoCloseable {

  private static final int OK = 200;
  private static final int FOUND = 302;
  private static final int NO_BACKLOG = 0;
  private static final int CLOSE_NOW = 0;

  private final HttpServer server;
  private final String host;

  /** The headers of the last request to reach each path. */
  private final Map<String, RequestHeaders> requestHeaders = new ConcurrentHashMap<>();

  private RecordingHttpServer(HttpServer server, String host) {
    this.server = server;
    this.host = host;
  }

  /** Starts a server bound to {@code host}, which must resolve to a loopback address. */
  static RecordingHttpServer start(String host) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(host, 0), NO_BACKLOG);
    server.start();
    return new RecordingHttpServer(server, host);
  }

  /** Answers {@code 200 body} on {@code path}. */
  RecordingHttpServer ok(String path, String body) {
    return handle(path, OK, body, null);
  }

  /** Answers {@code 302} on {@code path}, pointing at {@code location}. */
  RecordingHttpServer redirect(String path, String location) {
    return handle(path, FOUND, "", location);
  }

  /** Answers {@code status} on {@code path}, pointing at {@code location}. */
  RecordingHttpServer redirect(String path, int status, String location) {
    return handle(path, status, "", location);
  }

  /** The values of {@code X-Diagrid-User-Token} seen on {@code path}; empty when it carried none. */
  List<String> identityHeader(String path) {
    return header(path, IdentityContext.USER_TOKEN_HEADER);
  }

  /** The values of {@code name} seen on {@code path}; empty when the request carried none. */
  List<String> header(String path, String name) {
    RequestHeaders seen = requestHeaders.get(path);
    return seen == null ? List.of() : seen.get(name);
  }

  /** Whether any request reached {@code path} at all. */
  boolean wasCalled(String path) {
    return requestHeaders.containsKey(path);
  }

  URI uri(String path) {
    return URI.create("http://" + host + ":" + server.getAddress().getPort() + path);
  }

  @Override
  public void close() {
    server.stop(CLOSE_NOW);
  }

  private RecordingHttpServer handle(String path, int status, String body, String location) {
    server.createContext(path, exchange -> respond(exchange, path, status, body, location));
    return this;
  }

  private void respond(HttpExchange exchange, String path, int status, String body, String location)
      throws IOException {
    requestHeaders.put(path, RequestHeaders.of(exchange.getRequestHeaders()));
    if (location != null) {
      exchange.getResponseHeaders().add("Location", location);
    }
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  /**
   * The headers of one request, looked up case-insensitively as HTTP defines them.
   *
   * <p>Copied out of the exchange rather than held by reference: the server reuses the exchange, so
   * an assertion made later would otherwise read whatever it was reused for.
   */
  private record RequestHeaders(Map<String, List<String>> byLowercaseName) {

    static RequestHeaders of(Headers headers) {
      Map<String, List<String>> copy = new HashMap<>();
      headers.forEach((name, values) -> copy.put(lower(name), List.copyOf(values)));
      return new RequestHeaders(Map.copyOf(copy));
    }

    List<String> get(String name) {
      return byLowercaseName.getOrDefault(lower(name), List.of());
    }

    private static String lower(String name) {
      return name.toLowerCase(Locale.ROOT);
    }
  }
}
