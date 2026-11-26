package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Reo destination: its gate, which is stricter than Scarf's. Every env here is an explicit map,
 * so a test never reads the real environment and none passes vacuously because CI is set.
 */
class UsageAnalyticsReoTest {

  private static final String ENDPOINT = "https://example.invalid/java-ai";

  @BeforeEach
  void resetGuard() {
    UsageAnalytics.resetForTesting();
  }

  // ---------------------------------------------------------------- gate

  @Test
  void reoGateIsOpenOnACleanDeveloperMachine() {
    assertTrue(analytics(cleanEnv()).reoAllowed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"DO_NOT_TRACK", "SCARF_NO_ANALYTICS", "DIAGRID_NO_ANALYTICS"})
  void reoGateIsClosedOnOptOut(String variable) {
    assertFalse(analytics(envWith(variable, "1")).reoAllowed());
  }

  @Test
  void reoGateIsClosedInKubernetes() {
    assertFalse(analytics(envWith("KUBERNETES_SERVICE_HOST", "10.0.0.1")).reoAllowed());
  }

  @ParameterizedTest
  @CsvSource({
    "DAPR_API_TOKEN,t",
    "DAPR_GRPC_ENDPOINT,https://grpc.abc.diagrid.io:443",
    "DAPR_HTTP_ENDPOINT,https://http.abc.diagrid.io"
  })
  void reoGateIsClosedWhenPointedAtCatalyst(String name, String value) {
    assertFalse(analytics(envWith(name, value)).reoAllowed());
  }

  @Test
  void reoGateTreatsAPlainDaprSidecarAsNotCatalyst() {
    assertTrue(analytics(envWith("DAPR_GRPC_ENDPOINT", "http://localhost:50001")).reoAllowed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"CI", "GITHUB_ACTIONS", "GITLAB_CI", "CIRCLECI", "TRAVIS", "TF_BUILD", "BUILDKITE", "JENKINS_URL"})
  void reoGateIsClosedInCi(String variable) {
    assertFalse(analytics(envWith(variable, "true")).reoAllowed());
  }

  @Test
  void reoGateFailsClosedOnMalformedEndpoint() {
    // User-supplied, parsed as a URI, inside a SmartInitializingSingleton.
    assertFalse(analytics(envWith("DAPR_GRPC_ENDPOINT", "http://[:::::")).reoAllowed());
  }

  @Test
  void reoGateFailsClosedWhenTheEnvironmentLookupThrows() {
    Function<String, String> broken =
        name -> {
          throw new IllegalStateException("boom");
        };
    assertFalse(analytics(broken).reoAllowed());
  }

  @Test
  void reoGateIsNotVacuousUnderCi() {
    // Guards the Review Focus trap: the suite itself runs with CI=true in GitHub Actions.
    assertFalse(analytics(envWith("CI", "true")).reoAllowed());
    assertTrue(analytics(cleanEnv()).reoAllowed());
  }

  @Test
  void aMalformedEndpointStillReportsDaprToScarf() {
    // The Reo gate fails closed on it, but Scarf's target dimension must not change.
    assertTrue(
        "dapr".equals(analytics(envWith("DAPR_GRPC_ENDPOINT", "http://[:::::")).detectTarget()),
        "Scarf's target dimension changed for a malformed endpoint");
  }

  // ---------------------------------------------------------------- the Reo URL

  @Test
  void reoUrlMapsCollectedNamesToRegisteredKeys() {
    Map<String, String> query = query(reoUrl(springAiDimensions()));
    assertEquals("maven_runtime", query.get("activity_type"));
    assertEquals("diagrid-spring-ai-starter", query.get("product"));
    assertEquals("0.5.0", query.get("version"));
    assertEquals(System.getProperty("java.version"), query.get("jvm"));
    assertTrue(query.containsKey("platform"), "platform missing: " + query);
    assertEquals(System.getProperty("os.arch"), query.get("arch"));
    assertEquals("agent", query.get("kind"));
    assertEquals("SpringAI", query.get("framework"));
    assertEquals("1.0.3", query.get("framework_version"));
  }

  @Test
  void reoUrlStartsAtTheReoEndpoint() {
    assertTrue(reoUrl(springAiDimensions()).startsWith("https://reo.example.invalid/data?"));
  }

  @Test
  void reoUrlOmitsTargetAndCi() {
    Map<String, String> query = query(reoUrl(springAiDimensions()));
    assertFalse(query.containsKey("target"), "target sent to Reo: " + query);
    assertFalse(query.containsKey("ci"), "ci sent to Reo: " + query);
  }

  @Test
  void reoUrlOmitsTheUnmappedSourceNames() {
    Map<String, String> dimensions = springAiDimensions();
    dimensions.put("something_else", "x");
    Map<String, String> query = query(reoUrl(dimensions));
    for (String name : List.of("package", "java_version", "os", "something_else")) {
      assertFalse(query.containsKey(name), name + " sent to Reo: " + query);
    }
  }

  @Test
  void reoUrlDropsBlankValues() {
    Map<String, String> dimensions = springAiDimensions();
    dimensions.put("framework_version", "  ");
    assertFalse(query(reoUrl(dimensions)).containsKey("framework_version"));
  }

  @Test
  void reoUrlEscapesHostileValues() {
    Map<String, String> dimensions = springAiDimensions();
    dimensions.put("framework", "a&b=c");
    Map<String, String> query = query(reoUrl(dimensions));
    assertEquals("a&b=c", query.get("framework"));
    assertFalse(query.containsKey("b"), "a value split into a second parameter: " + query);
  }

  @Test
  void reoValuesAreTrimmedAndCappedAt64Characters() {
    Map<String, String> dimensions = springAiDimensions();
    dimensions.put("framework", "  " + "x".repeat(100) + "  ");
    assertEquals("x".repeat(64), query(reoUrl(dimensions)).get("framework"));
  }

  // ---------------------------------------------------------------- sending

  @Test
  void anOpenGateSendsOneScarfAndOneReoEvent() {
    List<String> urls = new CopyOnWriteArrayList<>();
    UsageAnalytics analytics =
        new UsageAnalytics(ENDPOINT, "https://reo.example.invalid/data", cleanEnv(), (url, ua) -> urls.add(url));

    analytics.reportEvent("diagrid-spring-ai-starter", "0.5.0", springAiDimensions());
    analytics.reportEvent("diagrid-spring-ai-starter", "0.5.0", springAiDimensions());

    assertEquals(2, urls.size(), "one event per destination: " + urls);
    assertTrue(urls.get(0).startsWith(ENDPOINT + "?package="), "Scarf first: " + urls);
    assertTrue(urls.get(1).startsWith("https://reo.example.invalid/data?activity_type=maven_runtime"));
  }

  @Test
  void inCiScarfStillSendsAndReoDoesNot() {
    List<String> urls = new CopyOnWriteArrayList<>();
    UsageAnalytics analytics =
        new UsageAnalytics(
            ENDPOINT, "https://reo.example.invalid/data", envWith("CI", "true"), (url, ua) -> urls.add(url));

    analytics.reportEvent("diagrid-spring-ai-starter", "0.5.0", springAiDimensions());

    assertEquals(1, urls.size(), urls.toString());
    assertTrue(urls.get(0).startsWith(ENDPOINT + "?"));
  }

  @Test
  void scarfUrlIsUnchangedByTheReoDestination() {
    String scarfOnly = new UsageAnalytics(ENDPOINT, cleanEnv(), (url, ua) -> { })
        .buildUrl("diagrid-spring-ai-starter", "0.5.0", springAiDimensions());
    String withReo = new UsageAnalytics(ENDPOINT, "https://reo.example.invalid/data", cleanEnv(), (url, ua) -> { })
        .buildUrl("diagrid-spring-ai-starter", "0.5.0", springAiDimensions());
    assertEquals(scarfOnly, withReo);
  }

  @Test
  void theDefaultReporterSendsToTheProductionReoEndpoint() {
    assertEquals("https://telemetry.diagrid.io/data", UsageAnalytics.REO_ENDPOINT);
  }

  @Test
  void reoRequestIsAGetWithQueryParametersAndNoAuthorizationHeader() throws Exception {
    try (RecordingServer server = new RecordingServer(true)) {
      UsageAnalytics.sendHttp(server.url() + "/data?activity_type=maven_runtime&product=p", "p/1");
      List<String> request = server.awaitRequest();
      assertEquals("GET /data?activity_type=maven_runtime&product=p HTTP/1.1", request.get(0));
      for (String header : request) {
        assertFalse(header.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:"), "auth header: " + request);
      }
    }
  }

  @Test
  void reportingReturnsAtFullSpeedWhenTheEndpointHangs() throws Exception {
    // The initializer runs on the application's startup path, so a server that never answers must
    // not hold it up.
    try (RecordingServer server = new RecordingServer(false)) {
      UsageAnalytics analytics =
          new UsageAnalytics("", server.url() + "/data", cleanEnv(), UsageAnalytics::sendHttp);
      long start = System.nanoTime();
      analytics.reportEvent("pkg-hang", "1.0.0", springAiDimensions());
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMillis < 200, "reportEvent blocked for " + elapsedMillis + " ms");
    }
  }

  // ---------------------------------------------------------------- test doubles

  private static String reoUrl(Map<String, String> dimensions) {
    return new UsageAnalytics(ENDPOINT, "https://reo.example.invalid/data", cleanEnv(), (url, ua) -> { })
        .buildReoUrl("diagrid-spring-ai-starter", "0.5.0", dimensions);
  }

  private static Map<String, String> springAiDimensions() {
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensions.put("kind", "agent");
    dimensions.put("framework", "SpringAI");
    dimensions.put("framework_version", "1.0.3");
    return dimensions;
  }

  private static Map<String, String> query(String url) {
    Map<String, String> result = new LinkedHashMap<>();
    String raw = URI.create(url).getRawQuery();
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      result.put(
          java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return result;
  }

  /** A local HTTP server: records the request line and headers, then answers 200 or never answers. */
  private static final class RecordingServer implements AutoCloseable {

    private final ServerSocket socket;
    private final List<String> lines = new ArrayList<>();
    private final CountDownLatch received = new CountDownLatch(1);
    private final Thread thread;

    RecordingServer(boolean answer) throws IOException {
      socket = new ServerSocket(0);
      thread =
          new Thread(
              () -> {
                try (Socket client = socket.accept();
                    BufferedReader in =
                        new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
                  String line;
                  while ((line = in.readLine()) != null && !line.isEmpty()) {
                    synchronized (lines) {
                      lines.add(line);
                    }
                  }
                  received.countDown();
                  if (answer) {
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                  } else {
                    Thread.sleep(5_000);
                  }
                } catch (IOException | InterruptedException e) {
                  // closing the server ends the thread
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    String url() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    List<String> awaitRequest() throws InterruptedException {
      assertTrue(received.await(5, TimeUnit.SECONDS), "no request arrived");
      synchronized (lines) {
        return List.copyOf(lines);
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
      thread.interrupt();
    }
  }

  private static UsageAnalytics analytics(Function<String, String> env) {
    return new UsageAnalytics(ENDPOINT, env, (url, userAgent) -> { });
  }

  /** No CI, opt-out, Kubernetes or Dapr variable is set: a developer's own machine. */
  private static Function<String, String> cleanEnv() {
    return name -> null;
  }

  private static Function<String, String> envWith(String name, String value) {
    Map<String, String> env = Map.of(name, value);
    return env::get;
  }
}
