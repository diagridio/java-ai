package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The remote metadata source: a sidecar reached over the network rather than over loopback.
 *
 * <p>Driven against a real loopback HTTP server, so every assertion is about the request that
 * actually left — its path, its headers — rather than about what the client believes it sent.
 */
class RemoteIdentityDiscoveryTest {

  private static final String LOOPBACK = "127.0.0.1";

  private static final String ISSUER = "https://oidc.test.com/org/region";

  private static final String API_TOKEN = "diagrid://v1/org/prj/token";

  /** Nothing listens on port 1, so a request to it fails without leaving the host. */
  private static final String UNREACHABLE_ENDPOINT = "http://" + LOOPBACK + ":1";

  private static final String REMOTE_BODY = "{\"id\": \"test-app\", \"identity\": {\"issuer\": \""
      + ISSUER + "\", \"jwks_uri\": \"" + ISSUER + "/keys\", \"audience\": \"agents\"}}";

  private MetadataServer sidecar;

  @AfterEach
  void stopSidecar() {
    if (sidecar != null) {
      sidecar.close();
      sidecar = null;
    }
  }

  private MetadataServer sidecarServing(String body) throws IOException {
    sidecar = MetadataServer.start().serving(body);
    return sidecar;
  }

  private static UnaryOperator<String> env(Map<String, String> values) {
    return values::get;
  }

  @Test
  @DisplayName("reads the identity block from a remote sidecar, trailing slash and all")
  void readsTheIdentityBlockFromARemoteSidecar() throws IOException {
    MetadataServer remote = sidecarServing(REMOTE_BODY);

    IdentityCoordinates coordinates = IdentityDiscovery.fromRemote(
        env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint() + "/")));

    assertNotNull(coordinates);
    assertEquals(ISSUER, coordinates.issuer());
    assertEquals(ISSUER + "/keys", coordinates.jwksUri());
    assertEquals("agents", coordinates.audience());
    // A trailing slash left on the endpoint would ask for //v1.0/metadata, which matches no context.
    assertEquals(List.of(IdentityDiscovery.METADATA_PATH), remote.requestedPaths());
  }

  @Test
  void remoteDiscoveryIsSkippedWithoutAnEndpoint() {
    assertNull(IdentityDiscovery.fromRemote(env(Map.of())));
    assertNull(IdentityDiscovery.fromRemote(env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, ""))));
  }

  @Test
  @DisplayName("the API token reaches the sidecar as the dapr-api-token header")
  void sendsTheApiTokenHeader() throws IOException {
    MetadataServer remote = sidecarServing(REMOTE_BODY);

    IdentityCoordinates coordinates = IdentityDiscovery.fromRemote(env(Map.of(
        IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint(),
        IdentityDiscovery.DAPR_API_TOKEN_ENV, API_TOKEN)));

    assertNotNull(coordinates);
    assertEquals(List.of(API_TOKEN), remote.apiTokensSeen());
    // The wire spelling is the contract; the sidecar looks for this header and no other.
    assertEquals("dapr-api-token", IdentityDiscovery.API_TOKEN_HEADER);
  }

  @Test
  @DisplayName("no API token in the environment means no such header on the request")
  void omitsTheApiTokenHeaderWhenTheTokenIsUnset() throws IOException {
    MetadataServer remote = sidecarServing(REMOTE_BODY);

    IdentityCoordinates coordinates = IdentityDiscovery.fromRemote(
        env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint())));

    // The request has to have arrived for its absent header to mean anything.
    assertNotNull(coordinates);
    assertEquals(List.of(""), remote.apiTokensSeen());
  }

  @Test
  @DisplayName("an unreachable remote sidecar discovers nothing rather than throwing")
  void remoteRequestFailureDiscoversNothing() {
    assertNull(IdentityDiscovery.fromRemote(
        env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, UNREACHABLE_ENDPOINT))));
  }

  @Test
  void malformedRemoteBodyDiscoversNothing() throws IOException {
    MetadataServer remote = sidecarServing("{}");
    UnaryOperator<String> env =
        env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint()));

    for (String body : List.of("[\"not\", \"an\", \"object\"]", "{\"id\": \"test-app\"}",
        "{\"identity\": {\"issuer\": 123}}", "{\"identity\": {\"issuer\": \"\"}}")) {
      remote.serving(body);

      assertNull(IdentityDiscovery.fromRemote(env), body + " must discover nothing");
    }
  }

  /** The level change is the point: a failed source has to be visible without turning on debug. */
  @Nested
  @DisplayName("discovery failures are logged at WARNING")
  class FailureLogging {

    @Test
    void unreachableRemoteSidecarWarns() {
      List<String> warnings;
      try (CapturedLogs logs = CapturedLogs.onDiscovery()) {
        assertNull(IdentityDiscovery.fromRemote(env(Map.of(
            IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, UNREACHABLE_ENDPOINT,
            IdentityDiscovery.DAPR_API_TOKEN_ENV, API_TOKEN))));
        warnings = logs.warnings();
      }

      String failure = onlyMatching(warnings, "trying the next source");
      assertTrue(failure.contains(UNREACHABLE_ENDPOINT + IdentityDiscovery.METADATA_PATH), failure);
      assertTrue(failure.contains("ConnectException"), failure);
      assertFalse(String.join("\n", warnings).contains(API_TOKEN), "the token must never be logged");
    }

    @Test
    @DisplayName("an unreachable local sidecar warns too, not only the remote one")
    void unreachableLocalSidecarWarns() {
      List<String> warnings;
      try (CapturedLogs logs = CapturedLogs.onDiscovery()) {
        assertNull(IdentityDiscovery.fromMetadata(env(Map.of(IdentityDiscovery.DAPR_HTTP_PORT_ENV, "1"))));
        warnings = logs.warnings();
      }

      String failure = onlyMatching(warnings, "trying the next source");
      assertTrue(failure.contains("http://" + LOOPBACK + ":1" + IdentityDiscovery.METADATA_PATH), failure);
    }

    @Test
    @DisplayName("an unset endpoint is an absent source, not a failure, and says nothing at all")
    void unsetEndpointLogsNothing() {
      List<String> records;
      try (CapturedLogs logs = CapturedLogs.onDiscovery()) {
        assertNull(IdentityDiscovery.fromRemote(env(Map.of())));
        records = logs.everything();
      }

      assertEquals(List.of(), records);
    }
  }

  /**
   * The API token on the way to the metadata endpoint, which is a different endpoint and a different
   * secret from the JWKS key set {@link JwksVerifier} guards.
   */
  @Nested
  @DisplayName("an API token bound for a plaintext endpoint")
  class PlaintextTokenWarning {

    @Test
    @DisplayName("warns about clear text but still sends the token: plain http is a valid setup")
    void warnsAndStillSends() throws IOException {
      MetadataServer remote = sidecarServing(REMOTE_BODY);
      List<String> warnings;
      try (CapturedLogs logs = CapturedLogs.onDiscovery()) {
        assertNotNull(IdentityDiscovery.fromRemote(env(Map.of(
            IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint(),
            IdentityDiscovery.DAPR_API_TOKEN_ENV, API_TOKEN))));
        warnings = logs.warnings();
      }

      String warning = onlyMatching(warnings, "clear text");
      assertTrue(warning.contains(remote.endpoint()), warning);
      assertFalse(warning.contains(API_TOKEN), "the token must never be logged");
      assertEquals(List.of(API_TOKEN), remote.apiTokensSeen(), "the token is still sent");
    }

    @Test
    @DisplayName("says nothing when the endpoint is https")
    void silentOverHttps() {
      // No https server is needed: the clear-text rule is decided from the endpoint before any
      // request is made, so an unreachable https endpoint exercises the same branch.
      List<String> warnings;
      try (CapturedLogs logs = CapturedLogs.onDiscovery()) {
        assertNull(IdentityDiscovery.fromRemote(env(Map.of(
            IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, "https://" + LOOPBACK + ":1",
            IdentityDiscovery.DAPR_API_TOKEN_ENV, API_TOKEN))));
        warnings = logs.warnings();
      }

      assertFalse(String.join("\n", warnings).contains("clear text"), String.join("\n", warnings));
    }
  }

  /**
   * Local before remote is deliberate: an in-cluster app must keep answering from loopback rather
   * than paying for a network round trip on every start.
   */
  @Nested
  @DisplayName("precedence")
  class Precedence {

    private static final String LOCAL_ISSUER = "http://" + LOOPBACK + ":1/local";

    private static final String REMOTE_ISSUER = "http://" + LOOPBACK + ":1/remote";

    private MetadataServer local;

    @AfterEach
    void stopLocal() {
      if (local != null) {
        local.close();
        local = null;
      }
    }

    private static String issuerBody(String issuer) {
      return "{\"identity\": {\"issuer\": \"" + issuer + "\"}}";
    }

    @Test
    @DisplayName("the local sidecar wins, and the remote one is never called")
    void localWinsOverRemote() throws IOException {
      local = MetadataServer.start().serving(issuerBody(LOCAL_ISSUER));
      MetadataServer remote = sidecarServing(issuerBody(REMOTE_ISSUER));

      JwksVerifier verifier = JwksVerifier.build(new OAuthConfig(), env(Map.of(
          IdentityDiscovery.DAPR_HTTP_PORT_ENV, String.valueOf(local.port()),
          IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint())));

      assertEquals(LOCAL_ISSUER, verifier.issuer());
      assertFalse(remote.wasCalled(), "the remote sidecar must not be consulted when local answers");
    }

    @Test
    @DisplayName("the remote sidecar answers when there is no local one")
    void remoteAnswersWithoutALocalSidecar() throws IOException {
      MetadataServer remote = sidecarServing(issuerBody(REMOTE_ISSUER));

      JwksVerifier verifier = JwksVerifier.build(new OAuthConfig(),
          env(Map.of(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, remote.endpoint())));

      assertEquals(REMOTE_ISSUER, verifier.issuer());
      assertEquals(REMOTE_ISSUER + "/jwks.json", verifier.jwksUri());
      assertTrue(remote.wasCalled());
    }

    @Test
    @DisplayName("a failing remote sidecar falls through to the environment")
    void remoteFailureFallsBackToTheEnvironment() {
      JwksVerifier verifier = JwksVerifier.build(new OAuthConfig(), env(Map.of(
          IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV, UNREACHABLE_ENDPOINT,
          IdentityDiscovery.ISSUER_ENV, "http://" + LOOPBACK + ":1/issuer")));

      assertEquals("http://" + LOOPBACK + ":1/issuer", verifier.issuer());
    }

    @Test
    @DisplayName("nothing configured names both ways to reach a sidecar")
    void unconfiguredMessageNamesBothEnvironmentVariables() {
      IdentityNotConfiguredException thrown = assertThrows(
          IdentityNotConfiguredException.class,
          () -> JwksVerifier.build(new OAuthConfig(), env(Map.of())));

      assertTrue(thrown.getMessage().contains(IdentityDiscovery.DAPR_HTTP_PORT_ENV), thrown.getMessage());
      assertTrue(thrown.getMessage().contains(IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV), thrown.getMessage());
      assertTrue(thrown.getMessage().contains(IdentityDiscovery.ISSUER_ENV), thrown.getMessage());
    }
  }

  /** The one log line containing {@code needle}, or a failure naming everything that was captured. */
  private static String onlyMatching(List<String> messages, String needle) {
    List<String> matches = messages.stream().filter(line -> line.contains(needle)).collect(Collectors.toList());
    assertEquals(1, matches.size(), "expected one line containing '" + needle + "' in " + messages);
    return matches.get(0);
  }

  /**
   * A loopback sidecar that records what every metadata request arrived with.
   *
   * <p>The served body is mutable so one server can stand in for several malformed answers.
   */
  private static final class MetadataServer implements AutoCloseable {

    private static final int NO_BACKLOG = 0;
    private static final int CLOSE_NOW = 0;
    private static final int OK = 200;

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> apiTokens = new CopyOnWriteArrayList<>();

    private MetadataServer(HttpServer server) {
      this.server = server;
    }

    static MetadataServer start() throws IOException {
      HttpServer http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), NO_BACKLOG);
      MetadataServer sidecar = new MetadataServer(http);
      http.createContext(IdentityDiscovery.METADATA_PATH, sidecar::respond);
      http.start();
      return sidecar;
    }

    MetadataServer serving(String json) {
      body.set(json);
      paths.clear();
      apiTokens.clear();
      return this;
    }

    int port() {
      return server.getAddress().getPort();
    }

    String endpoint() {
      return "http://" + LOOPBACK + ":" + port();
    }

    List<String> requestedPaths() {
      return List.copyOf(paths);
    }

    /** The API token each request carried, empty string when it carried no such header. */
    List<String> apiTokensSeen() {
      return List.copyOf(apiTokens);
    }

    boolean wasCalled() {
      return !paths.isEmpty();
    }

    @Override
    public void close() {
      server.stop(CLOSE_NOW);
    }

    private void respond(HttpExchange exchange) throws IOException {
      paths.add(exchange.getRequestURI().getPath());
      String token = exchange.getRequestHeaders().getFirst(IdentityDiscovery.API_TOKEN_HEADER);
      apiTokens.add(token == null ? "" : token);
      byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(OK, payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    }
  }

  /**
   * Captures what discovery logs.
   *
   * <p>{@code System.Logger} routes to {@code java.util.logging} on this module's JDK-only
   * dependency set, so a handler on the matching JUL logger is what sees the records. The logger's
   * level is widened for the duration and restored on close.
   */
  private static final class CapturedLogs implements AutoCloseable {

    private final java.util.logging.Logger logger;
    private final Handler handler;
    private final Level restoreLevel;
    private final boolean restoreParentHandlers;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private CapturedLogs(java.util.logging.Logger logger) {
      this.logger = logger;
      this.restoreLevel = logger.getLevel();
      this.restoreParentHandlers = logger.getUseParentHandlers();
      this.handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {
          // Nothing is buffered.
        }

        @Override
        public void close() {
          // Nothing is buffered.
        }
      };
      handler.setLevel(Level.ALL);
      logger.addHandler(handler);
      logger.setLevel(Level.ALL);
      logger.setUseParentHandlers(false);
    }

    static CapturedLogs onDiscovery() {
      return new CapturedLogs(java.util.logging.Logger.getLogger(IdentityDiscovery.class.getName()));
    }

    List<String> warnings() {
      return records.stream()
          .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
          .map(CapturedLogs::render)
          .collect(Collectors.toList());
    }

    List<String> everything() {
      return records.stream().map(CapturedLogs::render).collect(Collectors.toList());
    }

    @Override
    public void close() {
      logger.removeHandler(handler);
      logger.setLevel(restoreLevel);
      logger.setUseParentHandlers(restoreParentHandlers);
    }

    private static String render(LogRecord record) {
      String message = new SimpleFormatter().formatMessage(record);
      Throwable thrown = record.getThrown();
      return thrown == null ? message : message + " " + thrown;
    }
  }
}
