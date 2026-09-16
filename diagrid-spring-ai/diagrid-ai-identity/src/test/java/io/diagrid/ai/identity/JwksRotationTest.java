package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Key rotation against a real JWKS endpoint.
 *
 * <p>An issuer that rotates its signing key starts serving tokens under a {@code kid} the cached
 * key set has never seen. If the verifier waited for
 * {@link JwksVerifier#JWKS_CACHE_LIFETIME_SECONDS} to elapse before looking again, every caller
 * would get a 503 for up to five minutes on each rotation.
 *
 * <p>The endpoint counts its fetches, so "picked the new key up" is distinguished from "happened to
 * refetch on a timer".
 */
class JwksRotationTest {

  private static final String FIRST_KEY_ID = "rotation-key-1";
  private static final String SECOND_KEY_ID = "rotation-key-2";
  private static final String JWKS_PATH = "/jwks.json";

  private final AtomicReference<String> publishedKeys = new AtomicReference<>("{\"keys\":[]}");
  private final AtomicInteger fetches = new AtomicInteger();

  private HttpServer server;
  private String jwksUri;

  @BeforeEach
  void startJwksEndpoint() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(JWKS_PATH, exchange -> {
      fetches.incrementAndGet();
      byte[] body = publishedKeys.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    jwksUri = "http://127.0.0.1:" + server.getAddress().getPort() + JWKS_PATH;
  }

  @AfterEach
  void stopJwksEndpoint() {
    server.stop(0);
  }

  private static String tokenFrom(TestTokens keypair) {
    return keypair.signRs256(new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer(TestTokens.ISSUER)
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build());
  }

  @Test
  @DisplayName("a token signed by a rotated key verifies without waiting for the cache to expire")
  void rotatedKeyIsPickedUpOnTheFirstTokenThatNeedsIt() {
    TestTokens original = TestTokens.generate(FIRST_KEY_ID);
    publishedKeys.set(original.publicJwkSetJson());
    JwksVerifier verifier = new JwksVerifier(TestTokens.ISSUER, "", jwksUri);

    assertEquals("alice@example.com", verifier.verify(tokenFrom(original)).get("sub"));
    int fetchesAfterFirstUse = fetches.get();
    assertTrue(fetchesAfterFirstUse > 0, "the key set must have been fetched at least once");

    // The issuer rotates. The cached key set is still well inside its 300s lifetime, and it does not
    // contain the new kid.
    TestTokens rotated = TestTokens.generate(SECOND_KEY_ID);
    publishedKeys.set(rotated.publicJwkSetJson());

    assertEquals("alice@example.com", verifier.verify(tokenFrom(rotated)).get("sub"));
    assertTrue(
        fetches.get() > fetchesAfterFirstUse,
        "an unknown kid must force a re-fetch rather than wait out the cache lifetime");
  }

  @Test
  @DisplayName("a kid that no rotation ever published is still 'not ready', not 'invalid'")
  void unknownKidThatIsNotARotationStaysNotReady() {
    TestTokens published = TestTokens.generate(FIRST_KEY_ID);
    publishedKeys.set(published.publicJwkSetJson());
    JwksVerifier verifier = new JwksVerifier(TestTokens.ISSUER, "", jwksUri);
    assertEquals("alice@example.com", verifier.verify(tokenFrom(published)).get("sub"));

    String strayToken = TestTokens.generate("never-published").signRs256(new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer(TestTokens.ISSUER)
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build());

    assertThrows(VerifierNotReadyException.class, () -> verifier.verify(strayToken));
  }
}
