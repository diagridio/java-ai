package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
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

  // ---------------------------------------------------------------- test doubles

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
