package io.diagrid.ai.identity;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Finds the issuer, JWKS endpoint and audience an app should verify tokens against.
 *
 * <p>The sidecar knows these values and the app does not need to: asking it at startup is what lets
 * the same image run in any project or region without a config change. The environment variables
 * are the escape hatch for deployments with no sidecar, and they lose to anything the caller set
 * explicitly.
 *
 * <p>In a cluster the sidecar answers on loopback, which is the cheap path and so the one tried
 * first; {@code DAPR_HTTP_ENDPOINT} reaches one over the network when nothing is on loopback.
 *
 * <p>Discovery never throws on a network failure — an unreachable sidecar returns {@code null} so
 * the next source gets a turn. Refusing to serve is {@link JwksVerifier}'s decision to make, once
 * every source has been tried.
 */
final class IdentityDiscovery {

  /** Read before {@code DAPR_HTTP_PORT}: Catalyst sets it when the two disagree. */
  static final String CATALYST_DAPR_HTTP_PORT_ENV = "CATALYST_DAPR_HTTP_PORT";

  static final String DAPR_HTTP_PORT_ENV = "DAPR_HTTP_PORT";

  /** Base URL of a sidecar reached over the network, set when the app runs outside the cluster. */
  static final String DAPR_HTTP_ENDPOINT_ENV = "DAPR_HTTP_ENDPOINT";

  /** The token a remote sidecar requires on every call; a local one needs none. */
  static final String DAPR_API_TOKEN_ENV = "DAPR_API_TOKEN";

  /** The header the sidecar reads the API token from. Lowercase is the documented spelling. */
  static final String API_TOKEN_HEADER = "dapr-api-token";

  static final String ISSUER_ENV = "DIAGRID_DP_SENTRY_ISSUER";

  static final String AUDIENCE_ENV = "DIAGRID_DP_SENTRY_AUDIENCE";

  /** The sidecar is always loopback-local; never resolve it over the network. */
  static final String METADATA_HOST = "127.0.0.1";

  static final String METADATA_PATH = "/v1.0/metadata";

  /** Conventional location of an issuer's key set when the metadata block does not name one. */
  static final String JWKS_PATH_SUFFIX = "/jwks.json";

  /** Long enough for a cold sidecar, short enough not to stall app startup behind a dead one. */
  static final Duration METADATA_TIMEOUT = Duration.ofSeconds(5);

  private static final String IDENTITY_BLOCK = "identity";
  private static final String ISSUER_KEY = "issuer";
  private static final String JWKS_URI_KEY = "jwks_uri";
  private static final String AUDIENCE_KEY = "audience";

  private static final String HTTPS_PREFIX = "https://";

  /** A configured source that did not answer is warned about: the fallback may not be intended. */
  private static final String DISCOVERY_FAILED_MESSAGE =
      "identity discovery via {0} failed ({1}: {2}); trying the next source";

  private static final String PLAINTEXT_TOKEN_MESSAGE =
      DAPR_API_TOKEN_ENV + " will be sent in clear text to non-https endpoint {0}";

  /** Stands in for an exception type when the endpoint answered, but not with success. */
  private static final String HTTP_STATUS_PREFIX = "HTTP ";

  private static final String REFUSED_MESSAGE = "the metadata endpoint did not answer with success";

  private static final Logger LOGGER = System.getLogger(IdentityDiscovery.class.getName());

  /**
   * One client for every discovery attempt: {@code HttpClient} is thread-safe, and it is not
   * {@code AutoCloseable} before Java 21, so a per-call instance would leak its executor on this
   * module's Java 17 baseline.
   */
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(METADATA_TIMEOUT).build();

  private IdentityDiscovery() {
  }

  /**
   * Asks the local sidecar's metadata endpoint for the identity block.
   *
   * @param env environment lookup, so tests can drive the port without mutating the real environment
   * @return the coordinates the sidecar advertises, or {@code null} when there is no sidecar, no
   *     identity block, or the request fails
   */
  static IdentityCoordinates fromMetadata(UnaryOperator<String> env) {
    String port = firstNonEmpty(env.apply(CATALYST_DAPR_HTTP_PORT_ENV), env.apply(DAPR_HTTP_PORT_ENV));
    if (port.isEmpty()) {
      return null;
    }
    return fetchCoordinates("http://" + METADATA_HOST + ":" + port + METADATA_PATH, "");
  }

  /**
   * Asks a sidecar reached over the network for the identity block.
   *
   * <p>An app running on a developer's machine against a hosted sidecar has nothing on loopback, so
   * the endpoint is the only way to ask. An unset endpoint is an absent source, not a failure.
   *
   * @param env environment lookup, so tests can drive the endpoint without mutating the real
   *     environment
   * @return the coordinates the sidecar advertises, or {@code null} when no endpoint is configured,
   *     there is no identity block, or the request fails
   */
  static IdentityCoordinates fromRemote(UnaryOperator<String> env) {
    String endpoint = stripTrailingSlashes(firstNonEmpty(env.apply(DAPR_HTTP_ENDPOINT_ENV)));
    if (endpoint.isEmpty()) {
      return null;
    }
    String apiToken = firstNonEmpty(env.apply(DAPR_API_TOKEN_ENV));
    if (!apiToken.isEmpty() && !endpoint.startsWith(HTTPS_PREFIX)) {
      // Warned about but still sent: a self-hosted sidecar on plain http is a valid setup, and
      // refusing to look would leave the app with no coordinates at all.
      LOGGER.log(Level.WARNING, PLAINTEXT_TOKEN_MESSAGE, endpoint);
    }
    return fetchCoordinates(endpoint + METADATA_PATH, apiToken);
  }

  /**
   * Requests a metadata endpoint and reads the identity block out of the answer.
   *
   * <p>The one path both sources take, so local and remote discovery cannot drift in what they send,
   * how long they wait, or what they make of the answer.
   *
   * @param url      the metadata endpoint to ask
   * @param apiToken the API token to authenticate with; empty to send no token header
   * @return the coordinates, or {@code null} when the request fails or the body carries no issuer
   */
  private static IdentityCoordinates fetchCoordinates(String url, String apiToken) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(METADATA_TIMEOUT).GET();
      if (!apiToken.isEmpty()) {
        request.header(API_TOKEN_HEADER, apiToken);
      }
      HttpResponse<String> response = HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        LOGGER.log(Level.WARNING, DISCOVERY_FAILED_MESSAGE, url,
            HTTP_STATUS_PREFIX + response.statusCode(), REFUSED_MESSAGE);
        return null;
      }
      return parseMetadata(JSONObjectUtils.parse(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      // The endpoint and the exception, never the request: the API token must not reach the log.
      LOGGER.log(Level.WARNING, DISCOVERY_FAILED_MESSAGE, url, e.getClass().getSimpleName(), e.getMessage());
      return null;
    }
  }

  /**
   * Reads the coordinates from the environment.
   *
   * @param env environment lookup
   * @return the coordinates, or {@code null} when no issuer is set
   */
  static IdentityCoordinates fromEnvironment(UnaryOperator<String> env) {
    String issuer = firstNonEmpty(env.apply(ISSUER_ENV));
    if (issuer.isEmpty()) {
      return null;
    }
    return new IdentityCoordinates(issuer, defaultJwksUri(issuer), firstNonEmpty(env.apply(AUDIENCE_ENV)));
  }

  /**
   * The key set an issuer publishes when the metadata block does not name one.
   *
   * @param issuer the issuer URL
   * @return the conventional JWKS URL for that issuer
   */
  static String defaultJwksUri(String issuer) {
    return stripTrailingSlashes(issuer) + JWKS_PATH_SUFFIX;
  }

  private static String stripTrailingSlashes(String url) {
    String base = url;
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  /**
   * Reads the identity block out of a metadata response body.
   *
   * <p>Called inside {@link #fetchCoordinates}'s {@code try}, so a body of an unexpected shape is
   * treated as no discovery rather than as an error.
   *
   * @param metadata the parsed response body
   * @return the coordinates it advertises, or {@code null} when it names no issuer
   */
  private static IdentityCoordinates parseMetadata(Map<String, Object> metadata) {
    Object block = metadata.get(IDENTITY_BLOCK);
    if (!(block instanceof Map<?, ?> identity)) {
      return null;
    }
    String issuer = firstNonEmpty(stringValue(identity, ISSUER_KEY));
    if (issuer.isEmpty()) {
      return null;
    }
    String jwksUri = firstNonEmpty(stringValue(identity, JWKS_URI_KEY), defaultJwksUri(issuer));
    return new IdentityCoordinates(issuer, jwksUri, firstNonEmpty(stringValue(identity, AUDIENCE_KEY)));
  }

  private static String stringValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value instanceof String text ? text : "";
  }

  private static String firstNonEmpty(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isEmpty()) {
        return candidate;
      }
    }
    return "";
  }
}
