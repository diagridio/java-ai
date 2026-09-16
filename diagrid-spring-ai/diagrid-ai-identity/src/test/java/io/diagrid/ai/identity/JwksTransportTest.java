package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The key set is the whole root of trust, so it must not be fetched in the clear. An issuer arriving
 * from the environment or from sidecar metadata is not trusted to be https.
 *
 * <p>Each case asserts the exception type rather than a message: {@link
 * IdentityNotConfiguredException} means the endpoint was refused before any byte left the host,
 * {@link VerifierNotReadyException} that it was allowed through and the fetch itself failed. Every
 * "allowed" case points at a host that cannot answer, so no test here touches a real network.
 *
 * <p>The verifier is built inside each assertion, not before it: a refused endpoint is refused
 * while the build is still on the stack, so there is no verifier to call {@code verify} on.
 */
class JwksTransportTest {

  /** RFC 2606 reserves {@code .invalid}, so these names resolve nowhere. */
  private static final String UNRESOLVABLE_HOST = "keys.example.invalid";

  /** Nothing listens on port 1, so a loopback connection is refused immediately. */
  private static final String REFUSED_PORT = "1";

  private static String token;

  @BeforeAll
  static void mintToken() {
    token = TestTokens.generate().signRs256(new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer(TestTokens.ISSUER)
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build());
  }

  private static RuntimeException outcomeOf(String jwksUri) {
    return outcomeOf(jwksUri, false);
  }

  private static RuntimeException outcomeOf(String jwksUri, boolean allowInsecureJwks) {
    return assertThrows(RuntimeException.class, () -> {
      TokenVerifier verifier = new JwksVerifier(TestTokens.ISSUER, "", jwksUri, allowInsecureJwks);
      verifier.verify(token);
    });
  }

  private static void assertRefused(String jwksUri) {
    RuntimeException thrown = outcomeOf(jwksUri);

    assertEquals(IdentityNotConfiguredException.class, thrown.getClass(), jwksUri + " must be refused");
    assertTrue(thrown.getMessage().contains(jwksUri), "the message must name the endpoint refused");
  }

  private static void assertAllowed(String jwksUri) {
    assertEquals(
        VerifierNotReadyException.class,
        outcomeOf(jwksUri).getClass(),
        jwksUri + " must be allowed through to the fetch");
  }

  @Test
  @DisplayName("refuses to fetch signing keys over plaintext HTTP")
  void refusesPlaintext() {
    assertRefused("http://" + UNRESOLVABLE_HOST + "/jwks.json");
  }

  @Test
  @DisplayName("refuses a host that merely begins with a loopback name")
  void refusesLookalikeLoopbackHost() {
    assertRefused("http://localhost.attacker.invalid/jwks.json");
    assertRefused("http://127.0.0.1.attacker.invalid/jwks.json");
  }

  @Test
  @DisplayName("refuses a scheme that is neither http nor https")
  void refusesForeignScheme() {
    assertRefused("file:///etc/jwks.json");
  }

  @Test
  @DisplayName("allows https to any host")
  void allowsHttps() {
    assertAllowed("https://" + UNRESOLVABLE_HOST + "/jwks.json");
  }

  @Test
  @DisplayName("allows plaintext to loopback, where there is nothing on the wire to intercept")
  void allowsPlaintextOnLoopback() {
    assertAllowed("http://127.0.0.1:" + REFUSED_PORT + "/jwks.json");
    assertAllowed("http://localhost:" + REFUSED_PORT + "/jwks.json");
    assertAllowed("http://[::1]:" + REFUSED_PORT + "/jwks.json");
  }

  @Test
  @DisplayName("allowInsecureJwks opts a non-loopback host in to plaintext")
  void optingInAllowsPlaintextAnywhere() {
    String jwksUri = "http://" + UNRESOLVABLE_HOST + "/jwks.json";

    assertRefused(jwksUri);
    assertEquals(
        VerifierNotReadyException.class,
        outcomeOf(jwksUri, true).getClass(),
        "opting in must allow the endpoint through to the fetch");
  }

  @Test
  @DisplayName("opting in does not widen the scheme allowlist beyond http")
  void optingInDoesNotAllowForeignSchemes() {
    assertEquals(
        IdentityNotConfiguredException.class,
        outcomeOf("file:///etc/jwks.json", true).getClass(),
        "the opt-in covers plaintext http, not any scheme at all");
  }

  @Test
  @DisplayName("the opt-in reaches the verifier through OAuthConfig, not only the constructor")
  void configCarriesTheOptIn() {
    String jwksUri = "http://" + UNRESOLVABLE_HOST + "/jwks.json";

    // The refusal lands on build(), not on the first request: build() must not hand back a
    // verifier for a configuration that can never verify anything.
    assertThrows(
        IdentityNotConfiguredException.class,
        () -> JwksVerifier.build(
            new OAuthConfig(Set.of(), TestTokens.ISSUER, null, jwksUri, true, false)));

    TokenVerifier optedIn =
        JwksVerifier.build(new OAuthConfig(Set.of(), TestTokens.ISSUER, null, jwksUri, true, true));

    assertThrows(VerifierNotReadyException.class, () -> optedIn.verify(token));
  }

  @Test
  @DisplayName("an unusable JWKS URI is a configuration failure, not a caller-contract violation")
  void anUnparseableJwksUriIsRefusedAtBuildTime() {
    IdentityNotConfiguredException thrown = assertThrows(
        IdentityNotConfiguredException.class,
        () -> new JwksVerifier(TestTokens.ISSUER, "", "http://[not-a-host/jwks.json"));

    assertTrue(thrown.getMessage().contains("Not a usable JWKS URI"), thrown.getMessage());
  }

  @Test
  @DisplayName("a refused endpoint fails the build, so no verifier exists to answer a request")
  void aRefusedEndpointFailsTheBuild() {
    assertThrows(
        IdentityNotConfiguredException.class,
        () -> new JwksVerifier(TestTokens.ISSUER, "", "http://" + UNRESOLVABLE_HOST + "/jwks.json"));
  }
}
