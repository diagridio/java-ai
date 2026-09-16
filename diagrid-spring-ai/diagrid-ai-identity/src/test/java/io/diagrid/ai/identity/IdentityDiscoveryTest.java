package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Discovery is exercised against a real loopback HTTP server rather than a mocked client: the thing
 * most likely to break is the request itself — the path, the port precedence, the shape of the JSON.
 */
class IdentityDiscoveryTest {

  private static final String ISSUER = "https://oidc.test.com/org/region";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  /** Serves {@code body} at the metadata path and returns an env lookup pointing at it. */
  private UnaryOperator<String> sidecarServing(String body, String portVariable) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(IdentityDiscovery.METADATA_PATH, exchange -> {
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    server.start();
    return env(Map.of(portVariable, String.valueOf(server.getAddress().getPort())));
  }

  private static UnaryOperator<String> env(Map<String, String> values) {
    return values::get;
  }

  @Test
  void readsTheIssuerFromTheEnvironment() {
    IdentityCoordinates coordinates =
        IdentityDiscovery.fromEnvironment(env(Map.of(IdentityDiscovery.ISSUER_ENV, ISSUER)));

    assertEquals(ISSUER, coordinates.issuer());
    assertEquals(ISSUER + "/jwks.json", coordinates.jwksUri());
    assertEquals("", coordinates.audience());
  }

  @Test
  void readsTheAudienceFromTheEnvironment() {
    IdentityCoordinates coordinates = IdentityDiscovery.fromEnvironment(
        env(Map.of(IdentityDiscovery.ISSUER_ENV, ISSUER, IdentityDiscovery.AUDIENCE_ENV, "agents")));

    assertEquals("agents", coordinates.audience());
  }

  @Test
  @DisplayName("a trailing slash on the issuer does not produce a doubled slash in the JWKS URI")
  void trailingSlashOnIssuer() {
    IdentityCoordinates coordinates =
        IdentityDiscovery.fromEnvironment(env(Map.of(IdentityDiscovery.ISSUER_ENV, ISSUER + "/")));

    assertEquals(ISSUER + "/jwks.json", coordinates.jwksUri());
  }

  @Test
  void environmentDiscoveryIsSkippedWithoutAnIssuer() {
    assertNull(IdentityDiscovery.fromEnvironment(env(Map.of())));
  }

  @Test
  void readsTheIdentityBlockFromTheSidecar() throws IOException {
    UnaryOperator<String> env = sidecarServing(
        "{\"id\": \"test-app\", \"identity\": {\"issuer\": \"" + ISSUER
            + "\", \"jwks_uri\": \"" + ISSUER + "/keys\", \"audience\": \"agents\"}}",
        IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    IdentityCoordinates coordinates = IdentityDiscovery.fromMetadata(env);

    assertEquals(ISSUER, coordinates.issuer());
    assertEquals(ISSUER + "/keys", coordinates.jwksUri());
    assertEquals("agents", coordinates.audience());
  }

  @Test
  @DisplayName("derives the JWKS URI when the identity block names only an issuer")
  void metadataWithoutJwksUri() throws IOException {
    UnaryOperator<String> env = sidecarServing(
        "{\"identity\": {\"issuer\": \"" + ISSUER + "\"}}", IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    IdentityCoordinates coordinates = IdentityDiscovery.fromMetadata(env);

    assertEquals(ISSUER + "/jwks.json", coordinates.jwksUri());
    assertEquals("", coordinates.audience());
  }

  @Test
  @DisplayName("CATALYST_DAPR_HTTP_PORT wins over DAPR_HTTP_PORT")
  void catalystPortTakesPrecedence() throws IOException {
    UnaryOperator<String> live =
        sidecarServing("{\"identity\": {\"issuer\": \"" + ISSUER + "\"}}",
            IdentityDiscovery.CATALYST_DAPR_HTTP_PORT_ENV);
    UnaryOperator<String> env = key -> {
      // A dead port on the fallback variable: reading it instead would fail discovery outright.
      if (IdentityDiscovery.DAPR_HTTP_PORT_ENV.equals(key)) {
        return "1";
      }
      return live.apply(key);
    };

    assertEquals(ISSUER, IdentityDiscovery.fromMetadata(env).issuer());
  }

  @Test
  void metadataDiscoveryIsSkippedWithoutASidecarPort() {
    assertNull(IdentityDiscovery.fromMetadata(env(Map.of())));
  }

  @Test
  void metadataWithoutAnIdentityBlockDiscoversNothing() throws IOException {
    UnaryOperator<String> env =
        sidecarServing("{\"id\": \"test-app\"}", IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    assertNull(IdentityDiscovery.fromMetadata(env));
  }

  @Test
  void metadataWithAnEmptyIssuerDiscoversNothing() throws IOException {
    UnaryOperator<String> env =
        sidecarServing("{\"identity\": {\"issuer\": \"\"}}", IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    assertNull(IdentityDiscovery.fromMetadata(env));
  }

  @Test
  @DisplayName("an unreachable sidecar discovers nothing rather than throwing")
  void unreachableSidecar() {
    assertNull(IdentityDiscovery.fromMetadata(env(Map.of(IdentityDiscovery.DAPR_HTTP_PORT_ENV, "1"))));
  }

  @Test
  @DisplayName("explicit configuration is used without consulting the sidecar or the environment")
  void explicitConfigurationWins() {
    // A loopback JWKS URI so the warm-up build() performs cannot reach the network.
    String jwksUri = "http://127.0.0.1:1/keys";
    OAuthConfig config = new OAuthConfig(Set.of(), ISSUER, "agents", jwksUri, true);

    // A sidecar port that would answer differently if it were consulted at all.
    JwksVerifier verifier =
        JwksVerifier.build(config, env(Map.of(IdentityDiscovery.DAPR_HTTP_PORT_ENV, "1")));

    assertEquals(ISSUER, verifier.issuer());
    assertEquals("agents", verifier.audience());
    assertEquals(jwksUri, verifier.jwksUri());
  }

  @Test
  @DisplayName("the environment supplies coordinates when the sidecar has none")
  void environmentFallback() {
    JwksVerifier verifier = JwksVerifier.build(
        new OAuthConfig(), env(Map.of(IdentityDiscovery.ISSUER_ENV, "http://127.0.0.1:1/issuer")));

    assertEquals("http://127.0.0.1:1/issuer", verifier.issuer());
    assertEquals("http://127.0.0.1:1/issuer/jwks.json", verifier.jwksUri());
  }

  @Test
  @DisplayName("a jwks_uri the sidecar advertises away from its issuer is honoured, not overwritten")
  void advertisedJwksUriSurvivesTheBuild() throws IOException {
    String advertised = "https://keys.other.example/jwks";
    UnaryOperator<String> env = sidecarServing(
        "{\"identity\": {\"issuer\": \"" + ISSUER + "\", \"jwks_uri\": \"" + advertised + "\"}}",
        IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    JwksVerifier verifier = JwksVerifier.build(new OAuthConfig(), env);

    assertEquals(ISSUER, verifier.issuer());
    assertEquals(advertised, verifier.jwksUri(), "deriving issuer + /jwks.json would point at nothing");
  }

  @Test
  @DisplayName("a pinned issuer is never verified against a foreign issuer's advertised key set")
  void pinnedIssuerIgnoresAForeignJwksUri() throws IOException {
    UnaryOperator<String> env = sidecarServing(
        "{\"identity\": {\"issuer\": \"https://other.example\", "
            + "\"jwks_uri\": \"https://keys.other.example/jwks\"}}",
        IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    JwksVerifier verifier =
        JwksVerifier.build(new OAuthConfig(Set.of(), ISSUER, null, null, true), env);

    assertEquals(ISSUER, verifier.issuer());
    // Adopting the advertised key set would let a token minted by https://other.example and
    // claiming the pinned issuer verify.
    assertEquals(ISSUER + "/jwks.json", verifier.jwksUri());
  }

  @Test
  @DisplayName("an explicit jwks_uri outranks the one the sidecar advertises")
  void explicitJwksUriOutranksTheAdvertisedOne() throws IOException {
    String explicit = "https://keys.mine.example/jwks";
    UnaryOperator<String> env = sidecarServing(
        "{\"identity\": {\"issuer\": \"" + ISSUER + "\", "
            + "\"jwks_uri\": \"https://keys.other.example/jwks\"}}",
        IdentityDiscovery.DAPR_HTTP_PORT_ENV);

    JwksVerifier verifier =
        JwksVerifier.build(new OAuthConfig(Set.of(), null, null, explicit, true), env);

    assertEquals(explicit, verifier.jwksUri());
  }

  @Test
  @DisplayName("an issuer alone is enough: the JWKS URI is derived from it")
  void anIssuerAloneBuilds() {
    JwksVerifier verifier =
        JwksVerifier.build(new OAuthConfig(Set.of(), ISSUER + "/", null, null, true), env(Map.of()));

    assertEquals(ISSUER + "/jwks.json", verifier.jwksUri(), "the trailing slash must be trimmed");
  }

  @Test
  @DisplayName("no coordinates anywhere fails loudly rather than serving unverified requests")
  void noCoordinatesAnywhere() {
    IdentityNotConfiguredException thrown = assertThrows(
        IdentityNotConfiguredException.class,
        () -> JwksVerifier.build(new OAuthConfig(), env(Map.of())));

    assertTrue(thrown.getMessage().contains("Cannot discover identity coordinates"));
  }
}
