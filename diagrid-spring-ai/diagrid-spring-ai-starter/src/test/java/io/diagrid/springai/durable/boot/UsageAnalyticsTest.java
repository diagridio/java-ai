package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the anonymous usage reporter {@link UsageAnalytics}. Every test constructs its own
 * instance with a fake endpoint, an injected environment, and a recording/no-op {@link
 * UsageAnalytics.Sender}, so none of them ever touch the network. The per-process guard
 * ({@code REPORTED_PACKAGES}) is static and shared by every instance, so it is cleared before each
 * test; the package names used here are test-only strings, never the real
 * {@code diagrid-spring-ai-starter} package name the auto-configuration reports, so a test run can
 * never collide with (or be polluted by) a real report.
 */
class UsageAnalyticsTest {

  private static final Map<String, String> NO_DIMENSIONS = Map.of();
  private static final String ENDPOINT = "https://example.invalid/java-ai";

  @BeforeEach
  void resetGuard() {
    UsageAnalytics.resetForTesting();
  }

  // ---------------------------------------------------------------- opt-out parsing

  @Test
  void eachOptOutVariableDisablesReporting() {
    for (String name : UsageAnalytics.OPT_OUT_ENV_VARS) {
      UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, envWith(name, "1"), noopSender());
      assertTrue(analytics.isReportingDisabled(), name + " should disable reporting");
    }
  }

  @Test
  void noOptOutVariableSetLeavesReportingEnabled() {
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, noopSender());
    assertFalse(analytics.isReportingDisabled());
  }

  @Test
  void falsyOptOutValuesDoNotDisableReporting() {
    for (String value : List.of("0", "false", "no", "off", "")) {
      UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, envWith("DO_NOT_TRACK", value), noopSender());
      assertFalse(analytics.isReportingDisabled(), "'" + value + "' must not disable reporting");
    }
  }

  @Test
  void truthyOptOutValuesAreCaseAndSpaceInsensitive() {
    for (String value : List.of("1", "TRUE", " yes ", "On")) {
      UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, envWith("DO_NOT_TRACK", value), noopSender());
      assertTrue(analytics.isReportingDisabled(), "'" + value + "' must disable reporting");
    }
  }

  // ---------------------------------------------------------------- endpoint / per-process guard

  @Test
  void noSendWhenEndpointIsEmpty() throws InterruptedException {
    RecordingSender sender = new RecordingSender(1);
    UsageAnalytics analytics = new UsageAnalytics("", name -> null, sender);
    analytics.reportEvent("test-pkg-empty-endpoint", "1.0.0", NO_DIMENSIONS);
    assertFalse(sender.awaitAnyCall(200), "sender must not be called when the endpoint is empty");
  }

  @Test
  void noSendWhenOptedOut() throws InterruptedException {
    RecordingSender sender = new RecordingSender(1);
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, envWith("DO_NOT_TRACK", "1"), sender);
    analytics.reportEvent("test-pkg-opted-out", "1.0.0", NO_DIMENSIONS);
    assertFalse(sender.awaitAnyCall(200), "sender must not be called while opted out");
  }

  @Test
  void eventSentOncePerPackagePerProcess() throws InterruptedException {
    RecordingSender sender = new RecordingSender(2);
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, sender);

    analytics.reportEvent("test-pkg-a", "1.0.0", Map.of("kind", "agent"));
    analytics.reportEvent("test-pkg-a", "1.0.0", Map.of("kind", "agent"));
    analytics.reportEvent("test-pkg-b", "2.0.0", NO_DIMENSIONS);

    assertTrue(sender.awaitAnyCall(1000));
    assertEquals(2, sender.urls.size(), "the repeated package must be sent only once");
    assertTrue(sender.urls.get(0).contains("package=test-pkg-a"));
    assertTrue(sender.urls.get(1).contains("package=test-pkg-b"));
  }

  // ---------------------------------------------------------------- URL / dimensions

  @Test
  void urlContainsExpectedDimensions() {
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, noopSender());
    String url =
        analytics.buildUrl("test-pkg", "0.5.0", Map.of("kind", "agent", "framework", "SpringAI"));

    assertTrue(url.startsWith(ENDPOINT + "?"));
    for (String fragment :
        List.of(
            "package=test-pkg",
            "version=0.5.0",
            "os=",
            "arch=",
            "java_version=",
            "target=dapr",
            "ci=false",
            "kind=agent",
            "framework=SpringAI")) {
      assertTrue(url.contains(fragment), "missing '" + fragment + "' in " + url);
    }
  }

  @Test
  void callerDimensionsOverrideDefaultsAndEmptyOnesAreDropped() {
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, noopSender());
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensions.put("target", "catalyst");
    dimensions.put("framework", "");
    dimensions.put("framework_version", null);

    String url = analytics.buildUrl("test-pkg", "0.5.0", dimensions);

    assertTrue(url.contains("target=catalyst"), "caller-supplied target must win over the default");
    assertFalse(url.contains("framework="), "an empty caller dimension must be dropped");
    assertFalse(url.contains("framework_version="), "a null caller dimension must be dropped");
  }

  @Test
  void dimensionValuesAreTrimmedAndBoundedTo64Characters() {
    UsageAnalytics analytics = new UsageAnalytics(ENDPOINT, name -> null, noopSender());
    String longValue = "  " + "x".repeat(200) + "  ";

    String url = analytics.buildUrl("test-pkg", "0.5.0", Map.of("framework", longValue));

    // "framework" is appended after the built-in defaults, so it is not necessarily followed by
    // another "&"; asserting containment of the (bounded) value plus the absence of a 65th
    // character is order-independent.
    assertTrue(url.contains("framework=" + "x".repeat(64)));
    assertFalse(url.contains("x".repeat(65)));
  }

  // ---------------------------------------------------------------- target detection

  @Test
  void targetDetection() {
    assertEquals("dapr", new UsageAnalytics(ENDPOINT, name -> null, noopSender()).detectTarget());
    assertEquals(
        "dapr",
        new UsageAnalytics(ENDPOINT, envWith("DAPR_HTTP_ENDPOINT", "http://localhost:3500"), noopSender())
            .detectTarget());
    assertEquals(
        "catalyst",
        new UsageAnalytics(
                ENDPOINT, envWith("DAPR_GRPC_ENDPOINT", "https://grpc-prj1.api.cloud.diagrid.io:443"), noopSender())
            .detectTarget());
    assertEquals(
        "catalyst",
        new UsageAnalytics(
                ENDPOINT, envWith("DAPR_HTTP_ENDPOINT", "https://http-prj1.api.cloud.diagrid.io"), noopSender())
            .detectTarget());
    assertEquals(
        "catalyst",
        new UsageAnalytics(ENDPOINT, envWith("DAPR_GRPC_ENDPOINT", "grpc-prj1.api.cloud.diagrid.io:443"), noopSender())
            .detectTarget());
    assertEquals(
        "dapr",
        new UsageAnalytics(ENDPOINT, envWith("DAPR_GRPC_ENDPOINT", "https://notdiagrid.io:443"), noopSender())
            .detectTarget());
    assertEquals(
        "catalyst",
        new UsageAnalytics(ENDPOINT, envWith("DAPR_API_TOKEN", "diagrid://abc"), noopSender()).detectTarget());
    assertEquals(
        "dapr", new UsageAnalytics(ENDPOINT, envWith("DAPR_API_TOKEN", "   "), noopSender()).detectTarget());
  }

  // ---------------------------------------------------------------- ci detection

  @Test
  void ciDetection() {
    assertFalse(new UsageAnalytics(ENDPOINT, name -> null, noopSender()).isRunningInCi());
    assertTrue(new UsageAnalytics(ENDPOINT, envWith("CI", "true"), noopSender()).isRunningInCi());
    assertFalse(new UsageAnalytics(ENDPOINT, envWith("CI", "0"), noopSender()).isRunningInCi());
    assertTrue(new UsageAnalytics(ENDPOINT, envWith("GITHUB_ACTIONS", "true"), noopSender()).isRunningInCi());
    assertTrue(new UsageAnalytics(ENDPOINT, envWith("TF_BUILD", "True"), noopSender()).isRunningInCi());
    assertTrue(new UsageAnalytics(ENDPOINT, envWith("BUILDKITE", "true"), noopSender()).isRunningInCi());
    assertTrue(
        new UsageAnalytics(ENDPOINT, envWith("JENKINS_URL", "https://ci.example.invalid/"), noopSender())
            .isRunningInCi());
  }

  // ---------------------------------------------------------------- failure handling

  @Test
  void senderThatThrowsNeverPropagates() {
    UsageAnalytics analytics =
        new UsageAnalytics(
            ENDPOINT,
            name -> null,
            (url, userAgent) -> {
              throw new RuntimeException("boom");
            });
    // Reaching the end of this test (no exception escaping reportEvent) is the assertion.
    analytics.reportEvent("test-pkg-throwing-sender", "1.0.0", NO_DIMENSIONS);
  }

  // ---------------------------------------------------------------- version resolution

  @Test
  void resolveVersionFallsBackToUnknownWithoutManifestOrPomProperties() {
    assertEquals("unknown", UsageAnalytics.resolveVersion(UsageAnalyticsTest.class, "does-not-exist-artifact"));
  }

  // ---------------------------------------------------------------- test doubles

  private static Function<String, String> envWith(String name, String value) {
    return key -> key.equals(name) ? value : null;
  }

  private static UsageAnalytics.Sender noopSender() {
    return (url, userAgent) -> { };
  }

  /** Records every call and counts a latch down, so tests can await delivery without sleeping. */
  private static final class RecordingSender implements UsageAnalytics.Sender {

    private final List<String> urls = new CopyOnWriteArrayList<>();
    private final CountDownLatch latch;

    RecordingSender(int expectedCalls) {
      this.latch = new CountDownLatch(expectedCalls);
    }

    @Override
    public void send(String url, String userAgent) {
      urls.add(url);
      latch.countDown();
    }

    boolean awaitAnyCall(long timeoutMillis) throws InterruptedException {
      return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }
  }
}
