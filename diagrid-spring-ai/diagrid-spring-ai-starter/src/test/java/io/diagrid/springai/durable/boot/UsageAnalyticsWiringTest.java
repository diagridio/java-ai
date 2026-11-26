package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * The auto-configuration must hand the reporter a lookup backed by Spring's Environment. Without it,
 * a Catalyst endpoint set only in application.yml would not close the Reo gate.
 */
class UsageAnalyticsWiringTest {

  @BeforeEach
  void resetGuard() {
    UsageAnalytics.resetForTesting();
  }

  @Test
  void aCatalystEndpointInSpringConfigurationKeepsTheInitializerAwayFromReo() {
    List<String> urls = runInitializer(Map.of("dapr.client.grpc-endpoint", "https://grpc.abc.diagrid.io:443"));

    assertFalse(urls.stream().anyMatch(url -> url.startsWith(UsageAnalytics.REO_ENDPOINT)), "Reo was called: " + urls);
    assertTrue(urls.stream().anyMatch(url -> url.startsWith(UsageAnalytics.DEFAULT_ENDPOINT)), "Scarf missing: " + urls);
  }

  @Test
  void withNothingConfiguredTheInitializerReportsToReo() {
    // The counterpart: the same wiring with no Catalyst configuration does reach Reo.
    List<String> urls = runInitializer(Map.of());

    assertTrue(urls.stream().anyMatch(url -> url.startsWith(UsageAnalytics.REO_ENDPOINT)), "Reo missing: " + urls);
  }

  /**
   * Runs the initializer's work against a Spring Environment holding only {@code properties}. The
   * fallback lookup returns nothing, because the build runs test JVMs with DIAGRID_NO_ANALYTICS=1.
   */
  private static List<String> runInitializer(Map<String, Object> properties) {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    for (String name : sources.stream().map(source -> source.getName()).toList()) {
      sources.remove(name);
    }
    sources.addFirst(new MapPropertySource("application", properties));

    DiagridSpringAiAutoConfiguration configuration = new DiagridSpringAiAutoConfiguration();
    configuration.setEnvironment(environment);
    List<String> urls = new CopyOnWriteArrayList<>();
    configuration.reportUsage((url, userAgent) -> urls.add(url), name -> null);
    return urls;
  }
}
