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
import java.util.Collections;
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
import java.util.function.BooleanSupplier;
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
 * <p>Events go to a list of destinations, each with its own gate and URL format, and each package is
 * reported at most once per destination per process.
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

  /**
   * Reo's unauthenticated telemetry endpoint on Diagrid's tenant. No API key and no auth header:
   * nothing secret goes into a published artifact. Reo attributes the event to a company from the
   * request IP.
   */
  static final String REO_ENDPOINT = "https://telemetry.diagrid.io/data";

  /** The Reo activity type this artifact reports, as registered on the tenant. */
  private static final String REO_ACTIVITY_TYPE = "maven_runtime";

  /**
   * Collected dimension name to the key registered in Reo, in the order they are sent. Anything not
   * listed, including {@code target} and {@code ci}, is not sent to Reo: the Reo gate makes both
   * constant on every event.
   */
  private static final Map<String, String> REO_KEYS = reoKeys();

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

  /** Set inside every Kubernetes pod. Reo is never contacted from a cluster. */
  private static final String KUBERNETES_ENV_VAR = "KUBERNETES_SERVICE_HOST";

  private static final String CATALYST_HOST_SUFFIX = "diagrid.io";

  private static final int DIMENSION_MAX_LEN = 64;

  /**
   * Destination and package pairs already reported in this process, as {@code destination:package}.
   * Shared across every instance. Keyed per destination so a closed gate on one destination never
   * consumes another destination's single send.
   */
  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

  private static final ExecutorService EXECUTOR = newDaemonExecutor();

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(TIMEOUT).executor(EXECUTOR).build();

  private static final UsageAnalytics DEFAULT_INSTANCE =
      new UsageAnalytics(DEFAULT_ENDPOINT, REO_ENDPOINT, System::getenv, UsageAnalytics::sendHttp);

  private final String endpoint;
  private final String reoEndpoint;
  private final Function<String, String> env;
  private final Sender sender;
  private final List<Destination> destinations;

  /**
   * @param endpoint the event-collection URL; empty disables reporting
   * @param env      environment lookup, {@link System#getenv(String)} in production; injectable
   *                 because {@code System.getenv} cannot be stubbed from a test
   * @param sender   sends the built request; overridable so tests never touch the network
   */
  UsageAnalytics(String endpoint, Function<String, String> env, Sender sender) {
    this.endpoint = endpoint;
    this.reoEndpoint = "";
    this.env = env;
    this.sender = sender;
    this.destinations = List.of(new Destination("scarf", endpoint, () -> !isReportingDisabled(), this::buildUrl));
  }

  /**
   * A reporter that sends to Scarf and to Reo, each behind its own gate.
   *
   * @param endpoint    the Scarf event-collection URL; empty disables Scarf
   * @param reoEndpoint the Reo telemetry URL; empty disables Reo
   */
  UsageAnalytics(String endpoint, String reoEndpoint, Function<String, String> env, Sender sender) {
    this.endpoint = endpoint;
    this.reoEndpoint = reoEndpoint;
    this.env = env;
    this.sender = sender;
    this.destinations =
        List.of(
            new Destination("scarf", endpoint, () -> !isReportingDisabled(), this::buildUrl),
            new Destination("reo", reoEndpoint, this::reoAllowed, this::buildReoUrl));
  }

  private UsageAnalytics(Function<String, String> env, Sender sender, List<Destination> destinations) {
    this.endpoint = "";
    this.reoEndpoint = "";
    this.env = env;
    this.sender = sender;
    this.destinations = List.copyOf(destinations);
  }

  /** A reporter with an explicit destination list. Visible for tests only. */
  static UsageAnalytics withDestinations(
      Function<String, String> env, Sender sender, List<Destination> destinations) {
    return new UsageAnalytics(env, sender, destinations);
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

  /**
   * As {@link #report(String, String, Map)}, but every environment lookup (opt-out, CI, Kubernetes and
   * the Dapr target) goes through {@code env}, for example a lookup backed by Spring's Environment.
   * The per-process dedupe is shared with the default reporter.
   */
  static void report(
      String packageName, String version, Map<String, String> dimensions, Function<String, String> env) {
    new UsageAnalytics(DEFAULT_ENDPOINT, REO_ENDPOINT, env, UsageAnalytics::sendHttp)
        .reportEvent(packageName, version, dimensions);
  }

  /** Clears the per-process guard. Visible for tests only. */
  static void resetForTesting() {
    REPORTED.clear();
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
    for (Destination destination : destinations) {
      reportTo(destination, packageName, version, dimensions);
    }
  }

  /** Sends one destination's event. Never throws, so one destination cannot affect another. */
  private void reportTo(Destination destination, String packageName, String version, Map<String, String> dimensions) {
    try {
      if (destination.endpoint() == null || destination.endpoint().isEmpty()) {
        return;
      }
      if (!REPORTED.add(destination.name() + ":" + packageName)) {
        return;
      }
      if (!destination.gate().getAsBoolean()) {
        LOG.debug("Usage reporting for {} to {} is disabled", packageName, destination.name());
        return;
      }
      String url = destination.urlBuilder().build(packageName, version, dimensions);
      String userAgent = packageName + "/" + version;
      sender.send(url, userAgent);
    } catch (RuntimeException e) {
      LOG.debug("Usage event for {} to {} not sent: {}", packageName, destination.name(), e.toString());
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

  /**
   * The Reo gate. Stricter than Scarf's, which is the opt-out check alone. True only when no opt-out
   * variable is set, the process is not in a Kubernetes cluster, it is not pointed at Catalyst, and it
   * is not running in CI. Anything that cannot be determined, including an exception or a Dapr
   * endpoint that does not parse, means no event. The Spring property needs no check here: when it is
   * false the reporting bean does not exist.
   */
  boolean reoAllowed() {
    try {
      if (isReportingDisabled()) {
        return false;
      }
      String kubernetes = env.apply(KUBERNETES_ENV_VAR);
      if (kubernetes != null && !kubernetes.trim().isEmpty()) {
        return false;
      }
      if (hasUnparsableDaprEndpoint() || !"dapr".equals(detectTarget())) {
        return false;
      }
      return !isRunningInCi();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** True when a Dapr endpoint variable is set but its host can't be read. The Reo gate fails closed. */
  private boolean hasUnparsableDaprEndpoint() {
    for (String name : DAPR_ENDPOINT_ENV_VARS) {
      String raw = env.apply(name);
      if (raw == null || raw.trim().isEmpty()) {
        continue;
      }
      String trimmed = raw.trim();
      String candidate = trimmed.contains("://") ? trimmed : "https://" + trimmed;
      if (hostOf(candidate) == null) {
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

  /** Builds the Scarf event URL. Exposed package-private for tests. */
  String buildUrl(String packageName, String version, Map<String, String> dimensions) {
    return endpoint + "?" + encode(collect(packageName, version, dimensions));
  }

  /**
   * Builds the Reo event URL: {@code activity_type=maven_runtime} followed by the collected dimensions
   * under the names registered in Reo. Exposed package-private for tests.
   */
  String buildReoUrl(String packageName, String version, Map<String, String> dimensions) {
    Map<String, String> collected = collect(packageName, version, dimensions);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("activity_type", REO_ACTIVITY_TYPE);
    for (Map.Entry<String, String> key : REO_KEYS.entrySet()) {
      String value = clean(collected.get(key.getKey()));
      if (value != null) {
        params.put(key.getValue(), value);
      }
    }
    return reoEndpoint + "?" + encode(params);
  }

  /** The default dimensions plus the caller's, cleaned. Shared by every destination's URL builder. */
  private Map<String, String> collect(String packageName, String version, Map<String, String> dimensions) {
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
    return params;
  }

  private static String encode(Map<String, String> params) {
    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (query.length() > 0) {
        query.append('&');
      }
      query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      query.append('=');
      query.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return query.toString();
  }

  private static Map<String, String> reoKeys() {
    Map<String, String> keys = new LinkedHashMap<>();
    keys.put("package", "product");
    keys.put("version", "version");
    keys.put("java_version", "jvm");
    keys.put("os", "platform");
    keys.put("arch", "arch");
    keys.put("kind", "kind");
    keys.put("framework", "framework");
    keys.put("framework_version", "framework_version");
    return Collections.unmodifiableMap(keys);
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

  /** Builds one destination's event URL. */
  @FunctionalInterface
  interface UrlBuilder {
    String build(String packageName, String version, Map<String, String> dimensions);
  }

  /**
   * One place usage events go: its name (the dedupe key), its endpoint (empty disables it), the gate
   * that must be open for it to send, and how its URL is built.
   */
  record Destination(String name, String endpoint, BooleanSupplier gate, UrlBuilder urlBuilder) { }
}
