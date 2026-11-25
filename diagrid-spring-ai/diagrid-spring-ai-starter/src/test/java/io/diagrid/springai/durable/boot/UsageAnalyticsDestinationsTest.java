package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The destination list behind {@link UsageAnalytics}: Scarf's exact output, and how several
 * destinations are gated, deduplicated and isolated from each other.
 */
class UsageAnalyticsDestinationsTest {

  private static final String ENDPOINT = "https://example.invalid/java-ai";

  @BeforeEach
  void resetGuard() {
    UsageAnalytics.resetForTesting();
  }

  // ---------------------------------------------------------------- Scarf golden

  @Test
  void scarfUrlAndUserAgentHaveTheDocumentedShape() {
    String osName = System.getProperty("os.name");
    String osArch = System.getProperty("os.arch");
    String javaVersion = System.getProperty("java.version");
    try {
      System.setProperty("os.name", "Linux");
      System.setProperty("os.arch", "amd64");
      System.setProperty("java.version", "21.0.1");
      Recorder recorder = new Recorder();
      UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, recorder);
      Map<String, String> dimensions = new LinkedHashMap<>();
      dimensions.put("kind", "agent");
      dimensions.put("framework", "SpringAI");
      dimensions.put("framework_version", "1.0.0");

      analytics.reportEvent("diagrid-spring-ai-starter", "0.1.0", dimensions);

      assertEquals(
          List.of(
              "https://example.invalid/java-ai?package=diagrid-spring-ai-starter&version=0.1.0"
                  + "&os=linux&arch=amd64&java_version=21.0.1&target=dapr&ci=false"
                  + "&kind=agent&framework=SpringAI&framework_version=1.0.0"),
          recorder.urls);
      assertEquals(List.of("diagrid-spring-ai-starter/0.1.0"), recorder.userAgents);
    } finally {
      restore("os.name", osName);
      restore("os.arch", osArch);
      restore("java.version", javaVersion);
    }
  }

  // ---------------------------------------------------------------- destination list

  @Test
  void reportSendsToEachAllowedDestination() {
    Recorder recorder = new Recorder();
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(
            name -> null, recorder, List.of(destination("a", true), destination("b", true)));

    analytics.reportEvent("pkg-each", "1.0.0", Map.of());

    assertEquals(List.of("https://a.invalid/?package=pkg-each", "https://b.invalid/?package=pkg-each"), recorder.urls);
  }

  @Test
  void reportSkipsADestinationWhoseGateIsClosed() {
    Recorder recorder = new Recorder();
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(
            name -> null, recorder, List.of(destination("a", false), destination("b", true)));

    analytics.reportEvent("pkg-gate", "1.0.0", Map.of());

    assertEquals(List.of("https://b.invalid/?package=pkg-gate"), recorder.urls);
  }

  @Test
  void reportDedupesPerDestinationPerPackage() {
    Recorder recorder = new Recorder();
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(
            name -> null, recorder, List.of(destination("a", true), destination("b", true)));

    analytics.reportEvent("pkg-dedupe", "1.0.0", Map.of());
    analytics.reportEvent("pkg-dedupe", "1.0.0", Map.of());
    analytics.reportEvent("pkg-other", "1.0.0", Map.of());

    assertEquals(4, recorder.urls.size(), "one send per destination per package: " + recorder.urls);
  }

  @Test
  void aClosedGateOnOneDestinationDoesNotConsumeAnotherDestinationsSend() {
    Recorder recorder = new Recorder();
    // "a" is closed and reports first; it must not mark the package as reported for "b".
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(
            name -> null, recorder, List.of(destination("a", false), destination("b", true)));

    analytics.reportEvent("pkg-isolated", "1.0.0", Map.of());

    assertEquals(List.of("https://b.invalid/?package=pkg-isolated"), recorder.urls);
  }

  @Test
  void aDestinationThatThrowsDoesNotStopTheOthers() {
    Recorder recorder = new Recorder();
    UsageAnalytics.Destination throwingUrl =
        new UsageAnalytics.Destination(
            "url-throws",
            "https://x.invalid/",
            () -> true,
            (packageName, version, dimensions) -> {
              throw new IllegalStateException("boom");
            });
    UsageAnalytics.Destination throwingGate =
        new UsageAnalytics.Destination(
            "gate-throws",
            "https://y.invalid/",
            () -> {
              throw new IllegalStateException("boom");
            },
            (packageName, version, dimensions) -> "https://y.invalid/");
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(
            name -> null, recorder, List.of(throwingUrl, throwingGate, destination("b", true)));

    analytics.reportEvent("pkg-throws", "1.0.0", Map.of());

    assertEquals(List.of("https://b.invalid/?package=pkg-throws"), recorder.urls);
  }

  @Test
  void anEmptyEndpointIsANoOpForThatDestinationOnly() {
    Recorder recorder = new Recorder();
    UsageAnalytics.Destination empty =
        new UsageAnalytics.Destination(
            "empty", "", () -> true, (packageName, version, dimensions) -> "https://never.invalid/");
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(name -> null, recorder, List.of(empty, destination("b", true)));

    analytics.reportEvent("pkg-empty", "1.0.0", Map.of());

    assertEquals(List.of("https://b.invalid/?package=pkg-empty"), recorder.urls);
  }

  @Test
  void aSenderThatThrowsForOneDestinationStillReachesTheNext() {
    List<String> delivered = new CopyOnWriteArrayList<>();
    UsageAnalytics.Sender flaky =
        (url, userAgent) -> {
          if (url.startsWith("https://a.invalid/")) {
            throw new IllegalStateException("unreachable");
          }
          delivered.add(url);
        };
    UsageAnalytics analytics =
        UsageAnalytics.withDestinations(name -> null, flaky, List.of(destination("a", true), destination("b", true)));

    analytics.reportEvent("pkg-flaky", "1.0.0", Map.of());

    assertTrue(delivered.contains("https://b.invalid/?package=pkg-flaky"), "b was not reached: " + delivered);
  }

  // ---------------------------------------------------------------- test doubles

  private static UsageAnalytics.Destination destination(String name, boolean open) {
    return new UsageAnalytics.Destination(
        name,
        "https://" + name + ".invalid/",
        () -> open,
        (packageName, version, dimensions) -> "https://" + name + ".invalid/?package=" + packageName);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  /** Records every call synchronously; reportEvent hands the request to the sender on the caller's thread. */
  private static final class Recorder implements UsageAnalytics.Sender {

    private final List<String> urls = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();

    @Override
    public void send(String url, String userAgent) {
      urls.add(url);
      userAgents.add(userAgent);
    }
  }
}
