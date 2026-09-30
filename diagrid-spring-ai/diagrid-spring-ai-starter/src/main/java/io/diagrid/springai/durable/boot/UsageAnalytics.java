package io.diagrid.springai.durable.boot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anonymous usage reporting for the Diagrid Spring AI starter.
 *
 * <p>Maven Central publishes aggregate download counts only. This class reports one event per
 * package per process, carrying the artifact version and the host platform, so Diagrid can see
 * which versions run and where. No application data is collected. See the "Usage analytics"
 * section of the README, including how to opt out.
 *
 * <p>One event per process means one event per replica per restart on Kubernetes: the numbers
 * count process starts, not deployments or users.
 *
 * <p>The request never blocks the caller and never throws: it is dispatched with {@link
 * HttpClient#sendAsync} on a single-thread executor whose thread is a daemon, and every failure
 * is swallowed. The one second timeout bounds the connection and the request itself, not DNS
 * resolution, so a network that black-holes lookups can still take longer than a second before
 * the executor's thread is free again; the calling thread never waits on it either way. Blocked
 * egress and air-gapped clusters are normal conditions, not faults.
 *
 * <p>Two switches turn it off: the Spring property {@code diagrid.spring-ai.analytics.enabled=false},
 * which removes the reporting bean, and any of {@code DO_NOT_TRACK}, {@code SCARF_NO_ANALYTICS} or
 * {@code DIAGRID_NO_ANALYTICS} set to {@code 1}, {@code true}, {@code yes} or {@code on}
 * (case-insensitive). {@code 0}, {@code false} and an empty value do not opt out. The endpoint is
 * fixed at build time: an empty endpoint is a test seam, not a deployment option.
 */
final class UsageAnalytics {

  private static final Logger LOG = LoggerFactory.getLogger(UsageAnalytics.class);

  /**
   * Scarf event-collection route for java-ai (owner Diagrid). The route records the request and
   * redirects nowhere. Tests pass an empty endpoint through the package-private constructor to
   * make the reporter a no-op; there is no way to change it in a deployment.
   */
  static final String DEFAULT_ENDPOINT = "https://diagrid.gateway.scarf.sh/java-ai";

  private static final Duration TIMEOUT = Duration.ofSeconds(1);

  /** The cross-ecosystem convention, Scarf's own variable, and a Diagrid-specific opt-out. */
  static final List<String> OPT_OUT_ENV_VARS =
      List.of("DO_NOT_TRACK", "SCARF_NO_ANALYTICS", "DIAGRID_NO_ANALYTICS");

  private static final Set<String> TRUTHY_VALUES = Set.of("1", "true", "yes", "on");

  /** {@code CI} is the convention most vendors follow; the rest set their own flag instead. */
  static final List<String> CI_TRUTHY_ENV_VARS =
      List.of("CI", "GITHUB_ACTIONS", "GITLAB_CI", "CIRCLECI", "TRAVIS", "TF_BUILD");

  /** Vendors that set a value rather than a flag. Presence is enough. */
  static final List<String> CI_PRESENCE_ENV_VARS = List.of("BUILDKITE", "JENKINS_URL");

  /** The Dapr SDK variables that point a process at Catalyst. */
  static final List<String> DAPR_ENDPOINT_ENV_VARS = List.of("DAPR_GRPC_ENDPOINT", "DAPR_HTTP_ENDPOINT");

  private static final String DAPR_API_TOKEN_ENV_VAR = "DAPR_API_TOKEN";

  private static final String CATALYST_HOST_SUFFIX = "diagrid.io";

  private static final int DIMENSION_MAX_LEN = 64;

  /** Packages already reported in this process. Shared across every instance. */
  private static final Set<String> REPORTED_PACKAGES = ConcurrentHashMap.newKeySet();

  private static final ExecutorService EXECUTOR = newDaemonExecutor();

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(TIMEOUT).executor(EXECUTOR).build();

  private static final UsageAnalytics DEFAULT_INSTANCE =
      new UsageAnalytics(DEFAULT_ENDPOINT, System::getenv, UsageAnalytics::sendHttp);

  private final String endpoint;
  private final Function<String, String> env;
  private final Sender sender;

  /**
   * @param endpoint the event-collection URL; empty disables reporting
   * @param env      environment lookup, {@link System#getenv(String)} in production; injectable
   *                 because {@code System.getenv} cannot be stubbed from a test
   * @param sender   sends the built request; overridable so tests never touch the network
   */
  UsageAnalytics(String endpoint, Function<String, String> env, Sender sender) {
    this.endpoint = endpoint;
    this.env = env;
    this.sender = sender;
  }

  /**
   * Reports one anonymous usage event for {@code packageName}, once per package per process. See
   * the class Javadoc for what is sent, when it is skipped, and how to opt out. Never blocks the
   * caller and never throws.
   *
   * @param packageName the artifactId that hosts the call, e.g. {@code diagrid-spring-ai-starter}
   * @param version     the artifact version, or {@code "unknown"}
   * @param dimensions  extra query parameters, e.g. {@code kind} and {@code framework}; a {@code
   *                    null} map is treated as empty, a blank or {@code null} value is dropped,
   *                    and every value is trimmed and capped at 64 characters
   */
  static void report(String packageName, String version, Map<String, String> dimensions) {
    DEFAULT_INSTANCE.reportEvent(packageName, version, dimensions);
  }

  /** Clears the per-process guard. Visible for tests only. */
  static void resetForTesting() {
    REPORTED_PACKAGES.clear();
  }

  /**
   * Resolves the version of the jar hosting {@code anchor}: the embedded {@code
   * Implementation-Version} manifest entry when present, else the {@code version} property from
   * the {@code pom.properties} Maven bundles into every jar by default, else {@code "unknown"}.
   *
   * @param anchor     a class packaged inside the jar whose version is wanted
   * @param artifactId the Maven artifactId of that jar, under the {@code io.diagrid} group
   */
  static String resolveVersion(Class<?> anchor, String artifactId) {
    Package pkg = anchor.getPackage();
    String implementationVersion = pkg == null ? null : pkg.getImplementationVersion();
    if (implementationVersion != null && !implementationVersion.isBlank()) {
      return implementationVersion;
    }
    String resourcePath = "/META-INF/maven/io.diagrid/" + artifactId + "/pom.properties";
    try (InputStream in = anchor.getResourceAsStream(resourcePath)) {
      if (in != null) {
        Properties properties = new Properties();
        properties.load(in);
        String version = properties.getProperty("version");
        if (version != null && !version.isBlank()) {
          return version;
        }
      }
    } catch (IOException e) {
      LOG.debug("Could not read {} to resolve the {} version: {}", resourcePath, artifactId, e.toString());
    }
    return "unknown";
  }

  /** Instance form of {@link #report}, used directly by tests against a configured instance. */
  void reportEvent(String packageName, String version, Map<String, String> dimensions) {
    try {
      if (endpoint == null || endpoint.isEmpty()) {
        return;
      }
      if (!REPORTED_PACKAGES.add(packageName)) {
        return;
      }
      if (isReportingDisabled()) {
        LOG.debug("Usage reporting for {} is disabled by the environment", packageName);
        return;
      }
      String url = buildUrl(packageName, version, dimensions);
      String userAgent = packageName + "/" + version;
      sender.send(url, userAgent);
    } catch (RuntimeException e) {
      LOG.debug("Usage event for {} not sent: {}", packageName, e.toString());
    }
  }

  /** True when any opt-out variable is set to a truthy value. */
  boolean isReportingDisabled() {
    for (String name : OPT_OUT_ENV_VARS) {
      if (isTruthy(env.apply(name))) {
        return true;
      }
    }
    return false;
  }

  /** True when a well-known CI variable is set. Reported as the {@code ci} dimension. */
  boolean isRunningInCi() {
    for (String name : CI_TRUTHY_ENV_VARS) {
      if (isTruthy(env.apply(name))) {
        return true;
      }
    }
    for (String name : CI_PRESENCE_ENV_VARS) {
      String value = env.apply(name);
      if (value != null && !value.trim().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code catalyst} when the process points at Diagrid Catalyst, else {@code dapr}.
   *
   * <p>Catalyst is configured through the Dapr SDK endpoint variables: the endpoint host is
   * {@code diagrid.io} or a subdomain of it. Catalyst also issues {@code DAPR_API_TOKEN}, so a
   * self-hosted sidecar configured with an API token also reads as {@code catalyst}. That is an
   * approximation, and the dashboard reads it as one.
   */
  String detectTarget() {
    for (String name : DAPR_ENDPOINT_ENV_VARS) {
      String raw = env.apply(name);
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String candidate = trimmed.contains("://") ? trimmed : "https://" + trimmed;
      String host = hostOf(candidate);
      if (host != null) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.equals(CATALYST_HOST_SUFFIX) || lowerHost.endsWith("." + CATALYST_HOST_SUFFIX)) {
          return "catalyst";
        }
      }
    }
    String token = env.apply(DAPR_API_TOKEN_ENV_VAR);
    return token != null && !token.trim().isEmpty() ? "catalyst" : "dapr";
  }

  /** Builds the event URL. Exposed package-private for tests. */
  String buildUrl(String packageName, String version, Map<String, String> dimensions) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("package", packageName);
    params.put("version", version);
    params.put("os", osName());
    params.put("arch", System.getProperty("os.arch", "unknown"));
    params.put("java_version", System.getProperty("java.version", "unknown"));
    params.put("target", detectTarget());
    params.put("ci", isRunningInCi() ? "true" : "false");
    if (dimensions != null) {
      for (Map.Entry<String, String> entry : dimensions.entrySet()) {
        String cleaned = clean(entry.getValue());
        if (cleaned == null) {
          params.remove(entry.getKey());
        } else {
          params.put(entry.getKey(), cleaned);
        }
      }
    }
    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (query.length() > 0) {
        query.append('&');
      }
      query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      query.append('=');
      query.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return endpoint + "?" + query;
  }

  private static String hostOf(String candidate) {
    try {
      return URI.create(candidate).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isTruthy(String value) {
    return value != null && TRUTHY_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
  }

  private static String osName() {
    String lower = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (lower.contains("linux")) {
      return "linux";
    }
    if (lower.contains("mac") || lower.contains("darwin")) {
      return "darwin";
    }
    if (lower.contains("win")) {
      return "windows";
    }
    return lower;
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > DIMENSION_MAX_LEN ? trimmed.substring(0, DIMENSION_MAX_LEN) : trimmed;
  }

  // Package-private (not private) so a test in this package can hand it to a UsageAnalytics
  // instance as the real Sender, without going through the DEFAULT_INSTANCE's fixed endpoint.
  static void sendHttp(String url, String userAgent) {
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", userAgent).GET().build();
    } catch (RuntimeException e) {
      LOG.debug("Usage event not sent: {}", e.toString());
      return;
    }
    HTTP_CLIENT
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, throwable) -> {
              if (throwable != null) {
                LOG.debug("Usage event not sent: {}", throwable.toString());
              } else {
                LOG.debug("Usage event sent ({})", response.statusCode());
              }
            });
  }

  private static ExecutorService newDaemonExecutor() {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "diagrid-usage-analytics");
          thread.setDaemon(true);
          return thread;
        };
    return Executors.newSingleThreadExecutor(factory);
  }

  /** Sends the built request. A separate seam so tests never touch the network. */
  @FunctionalInterface
  interface Sender {
    void send(String url, String userAgent);
  }
}
