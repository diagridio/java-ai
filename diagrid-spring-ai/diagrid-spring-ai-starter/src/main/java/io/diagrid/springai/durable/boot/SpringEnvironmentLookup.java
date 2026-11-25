package io.diagrid.springai.durable.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.core.env.Environment;

/**
 * Builds the {@link UsageAnalytics} environment lookup from Spring's {@link Environment}.
 *
 * <p>The Dapr SDK in other languages is configured through environment variables, and {@link
 * UsageAnalytics#detectTarget()} reads those. A Spring application can instead point at Catalyst
 * through {@code application.yml}, a profile, or the Dapr Spring Boot starter's {@code dapr.client.*}
 * properties, with no Dapr environment variable set. This lookup lets the Reo gate see that.
 *
 * <p>For each name it tries, in order: the name itself (Spring resolves environment-variable-style
 * names against the system environment as well as properties and YAML), the name lower-cased with
 * underscores turned into dots, the matching Dapr Spring Boot starter properties, and finally {@link
 * System#getenv(String)}, so behaviour outside a Spring context is unchanged. It never throws.
 */
final class SpringEnvironmentLookup {

  /**
   * The Dapr Spring Boot starter's own properties ({@code DaprClientProperties}, prefix {@code
   * dapr.client}, read off {@code dapr-spring-boot-properties} 1.19.1) for each Dapr SDK variable, in
   * both kebab and camel case.
   */
  private static final Map<String, List<String>> DAPR_SPRING_PROPERTIES =
      Map.of(
          "DAPR_GRPC_ENDPOINT", List.of("dapr.client.grpc-endpoint", "dapr.client.grpcEndpoint"),
          "DAPR_HTTP_ENDPOINT", List.of("dapr.client.http-endpoint", "dapr.client.httpEndpoint"),
          "DAPR_API_TOKEN", List.of("dapr.client.api-token", "dapr.client.apiToken"));

  private SpringEnvironmentLookup() {
  }

  /** A lookup backed by {@code environment}; a {@code null} environment falls back to getenv alone. */
  static Function<String, String> resolver(Environment environment) {
    return resolver(environment, System::getenv);
  }

  /**
   * As {@link #resolver(Environment)}, with an explicit last-resort lookup in place of {@code
   * System.getenv}. Visible for tests: the build runs every test JVM with {@code
   * DIAGRID_NO_ANALYTICS=1}, so a test that fell back to the real environment would always see an
   * opt-out.
   */
  static Function<String, String> resolver(Environment environment, Function<String, String> fallback) {
    return name -> resolve(environment, name, fallback);
  }

  /** The Spring property names tried for {@code name}, in order, before {@code System.getenv}. */
  static List<String> candidateNames(String name) {
    List<String> names = new ArrayList<>();
    names.add(name);
    names.add(name.toLowerCase(Locale.ROOT).replace('_', '.'));
    names.addAll(DAPR_SPRING_PROPERTIES.getOrDefault(name, List.of()));
    return names;
  }

  private static String resolve(Environment environment, String name, Function<String, String> fallback) {
    if (environment != null) {
      for (String candidate : candidateNames(name)) {
        String value = propertyOrNull(environment, candidate);
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    }
    return fallback.apply(name);
  }

  private static String propertyOrNull(Environment environment, String key) {
    try {
      return environment.getProperty(key);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
