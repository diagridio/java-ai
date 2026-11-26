package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * A Spring application can point at Catalyst through application.yml, a profile or the Dapr Spring
 * Boot starter's {@code dapr.client.*} properties with no Dapr environment variable set at all. The
 * Reo gate must see that configuration, or the artifact reports from the population it must not.
 */
class SpringEnvironmentLookupTest {

  private static final String ENDPOINT = "https://example.invalid/java-ai";

  @ParameterizedTest
  @CsvSource({
    "dapr.client.grpc-endpoint,https://grpc.abc.diagrid.io:443",
    "dapr.client.grpcEndpoint,https://grpc.abc.diagrid.io:443",
    "dapr.client.http-endpoint,https://http.abc.diagrid.io",
    "dapr.client.httpEndpoint,https://http.abc.diagrid.io",
    "dapr.grpc.endpoint,https://grpc.abc.diagrid.io:443",
    "dapr.http.endpoint,https://http.abc.diagrid.io",
    "DAPR_GRPC_ENDPOINT,https://grpc.abc.diagrid.io:443"
  })
  void reoGateIsClosedWhenCatalystIsConfiguredInSpringOnly(String property, String value) {
    UsageAnalytics analytics = analyticsFor(springEnvironment(Map.of(property, value)));
    assertEquals("catalyst", analytics.detectTarget());
    assertFalse(analytics.reoAllowed());
  }

  @ParameterizedTest
  @CsvSource({"dapr.client.api-token", "dapr.client.apiToken", "dapr.api.token"})
  void reoGateIsClosedWhenTheApiTokenComesFromSpringConfiguration(String property) {
    UsageAnalytics analytics = analyticsFor(springEnvironment(Map.of(property, "secret")));
    assertEquals("catalyst", analytics.detectTarget());
    assertFalse(analytics.reoAllowed());
  }

  @Test
  void aPlainLocalSidecarInSpringConfigurationLeavesTheGateOpen() {
    UsageAnalytics analytics =
        analyticsFor(springEnvironment(Map.of("dapr.client.grpc-endpoint", "http://localhost:50001")));
    assertEquals("dapr", analytics.detectTarget());
    assertTrue(analytics.reoAllowed());
  }

  @Test
  void withNothingConfiguredTheGateIsOpen() {
    // The counterpart that makes the closed-gate tests meaningful: the same hermetic lookup, no Catalyst.
    assertTrue(analyticsFor(springEnvironment(Map.of())).reoAllowed());
  }

  @Test
  void anOptOutCanComeFromSpringConfigurationToo() {
    UsageAnalytics analytics = analyticsFor(springEnvironment(Map.of("DO_NOT_TRACK", "1")));
    assertTrue(analytics.isReportingDisabled());
  }

  @Test
  void theResolverFallsBackToGetenvWhenSpringHasNothing() {
    StandardEnvironment empty = new StandardEnvironment();
    MutablePropertySources sources = empty.getPropertySources();
    for (String name : sources.stream().map(source -> source.getName()).toList()) {
      sources.remove(name);
    }
    assertNotNull(SpringEnvironmentLookup.resolver(empty).apply("PATH"));
  }

  @Test
  void aNullEnvironmentFallsBackToGetenv() {
    assertNotNull(SpringEnvironmentLookup.resolver(null).apply("PATH"));
  }

  @Test
  void anUnsetNameResolvesToNull() {
    Function<String, String> resolver = SpringEnvironmentLookup.resolver(springEnvironment(Map.of()));
    assertNull(resolver.apply("DIAGRID_TEST_SURELY_UNSET_" + System.nanoTime()));
  }

  @Test
  void aThrowingEnvironmentFallsBackToGetenvInsteadOfThrowing() {
    StandardEnvironment broken =
        new StandardEnvironment() {
          @Override
          public String getProperty(String key) {
            throw new IllegalStateException("boom");
          }
        };
    assertNotNull(SpringEnvironmentLookup.resolver(broken).apply("PATH"));
  }

  @Test
  void theAutoConfigurationReceivesTheSpringEnvironment() {
    // The initializer can only pass the resolver on if Spring hands the configuration its Environment.
    assertTrue(
        EnvironmentAware.class.isAssignableFrom(DiagridSpringAiAutoConfiguration.class),
        "DiagridSpringAiAutoConfiguration must implement EnvironmentAware");
  }

  @Test
  void theLookupOrderIsSpringFirstThenGetenv() {
    List<String> names = SpringEnvironmentLookup.candidateNames("DAPR_GRPC_ENDPOINT");
    assertEquals("DAPR_GRPC_ENDPOINT", names.get(0));
    assertTrue(names.contains("dapr.grpc.endpoint"));
    assertTrue(names.contains("dapr.client.grpc-endpoint"));
    assertTrue(names.contains("dapr.client.grpcEndpoint"));
  }

  // ---------------------------------------------------------------- test doubles

  /**
   * A reporter whose lookup sees only {@code environment}. The fallback returns nothing, because the
   * build runs test JVMs with DIAGRID_NO_ANALYTICS=1 and CI may set CI=true: falling back to the real
   * environment would close the gate for reasons that have nothing to do with Spring.
   */
  private static UsageAnalytics analyticsFor(StandardEnvironment environment) {
    return new UsageAnalytics(
        ENDPOINT, SpringEnvironmentLookup.resolver(environment, name -> null), (url, userAgent) -> { });
  }

  /** A real Spring Environment holding only the given properties, as application.yml would supply them. */
  private static StandardEnvironment springEnvironment(Map<String, Object> properties) {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    for (String name : sources.stream().map(source -> source.getName()).toList()) {
      sources.remove(name);
    }
    sources.addFirst(new MapPropertySource("application", properties));
    return environment;
  }
}
