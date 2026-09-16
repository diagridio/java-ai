package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verification is fail-closed, so most of what matters here is what gets rejected and with which
 * code. The codes are a cross-SDK contract, so each case asserts the exact string, not merely that
 * something was thrown.
 */
class JwksVerifierTest {

  private static TestTokens tokens;

  @BeforeAll
  static void generateKeypair() {
    tokens = TestTokens.generate();
  }

  private static JwksVerifier verifier() {
    return new JwksVerifier(TestTokens.ISSUER, "", tokens.publicJwkSource());
  }

  private static JwksVerifier verifierWithAudience(String audience) {
    return new JwksVerifier(TestTokens.ISSUER, audience, tokens.publicJwkSource());
  }

  private static JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer(TestTokens.ISSUER)
        .issueTime(secondsFromNow(0))
        .expirationTime(secondsFromNow(3600));
  }

  private static Date secondsFromNow(long seconds) {
    return new Date(System.currentTimeMillis() + seconds * 1000L);
  }

  private static String codeOf(TokenVerifier verifier, String token) {
    return assertThrows(TokenVerificationException.class, () -> verifier.verify(token)).code();
  }

  @Nested
  @DisplayName("accepts")
  class Accepts {

    @Test
    void validToken() {
      Map<String, Object> claims = verifier().verify(tokens.signRs256(validClaims().build()));

      assertEquals("alice@example.com", claims.get("sub"));
      assertEquals(TestTokens.ISSUER, claims.get("iss"));
    }

    @Test
    @DisplayName("a token expired inside the 120s clock-skew allowance")
    void tokenExpiredWithinClockSkew() {
      String token = tokens.signRs256(validClaims().expirationTime(secondsFromNow(-60)).build());

      assertEquals("alice@example.com", verifier().verify(token).get("sub"));
    }

    @Test
    @DisplayName("any audience when none is configured, matching the reference SDK")
    void anyAudienceWhenNoneConfigured() {
      String token = tokens.signRs256(validClaims().audience("some-other-service").build());

      assertEquals("alice@example.com", verifier().verify(token).get("sub"));
    }

    @Test
    void matchingAudienceAmongSeveral() {
      String token = tokens.signRs256(validClaims().audience(List.of("other", "agents")).build());

      assertEquals("alice@example.com", verifierWithAudience("agents").verify(token).get("sub"));
    }

    @Test
    @DisplayName("any issuer when none is configured")
    void anyIssuerWhenNoneConfigured() {
      TokenVerifier anyIssuer = new JwksVerifier("", "", tokens.publicJwkSource());
      String token = tokens.signRs256(validClaims().issuer("https://somewhere-else.example").build());

      assertEquals("alice@example.com", anyIssuer.verify(token).get("sub"));
    }

    @Test
    void exposesTheFullPayloadForRicherPolicies() {
      String token = tokens.signRs256(validClaims().claim("tid", "acme-corp").claim("scp", List.of("a")).build());

      Map<String, Object> claims = verifier().verify(token);

      assertEquals("acme-corp", claims.get("tid"));
      assertEquals(List.of("a"), claims.get("scp"));
    }
  }

  @Nested
  @DisplayName("rejects")
  class Rejects {

    @Test
    void expiredToken() {
      String token = tokens.signRs256(validClaims().expirationTime(secondsFromNow(-3600)).build());

      assertEquals(OAuthErrorCodes.EXPIRED, codeOf(verifier(), token));
    }

    @Test
    void wrongIssuer() {
      String token = tokens.signRs256(validClaims().issuer("https://wrong-issuer.example").build());

      assertEquals(OAuthErrorCodes.INVALID_ISSUER, codeOf(verifier(), token));
    }

    @Test
    void wrongAudience() {
      String token = tokens.signRs256(validClaims().audience("not-us").build());

      assertEquals(OAuthErrorCodes.INVALID_AUDIENCE, codeOf(verifierWithAudience("agents"), token));
    }

    @Test
    @DisplayName("a missing audience when one is configured")
    void missingAudienceWhenConfigured() {
      String token = tokens.signRs256(validClaims().build());

      assertEquals(OAuthErrorCodes.INVALID_AUDIENCE, codeOf(verifierWithAudience("agents"), token));
    }

    @Test
    @DisplayName("a signature made with a key the issuer never published")
    void badSignature() {
      String token = TestTokens.signWithWrongKey(validClaims().build());

      assertEquals(OAuthErrorCodes.INVALID_SIGNATURE, codeOf(verifier(), token));
    }

    @Test
    void garbageToken() {
      assertEquals(OAuthErrorCodes.DECODE_ERROR, codeOf(verifier(), "not-a-jwt"));
    }

    @Test
    void emptyToken() {
      assertEquals(OAuthErrorCodes.DECODE_ERROR, codeOf(verifier(), ""));
    }

    @Test
    @DisplayName("an HS256 token, so a leaked public key cannot be used as an HMAC secret")
    void hs256Token() {
      String token = TestTokens.signHs256(validClaims().build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    @DisplayName("an unsecured alg:none token, as a disallowed algorithm rather than as garbage")
    void unsecuredToken() {
      String token = TestTokens.unsecured(validClaims().build());

      // invalid_token, not decode_error: decode_error is reserved for a token that cannot be
      // parsed at all, which an alg:none JWT perfectly well can be.
      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    @DisplayName("a token whose header names no algorithm at all")
    void tokenWithoutAnAlgorithmHeader() {
      String token = TestTokens.withRawHeader("{\"typ\":\"JWT\"}", validClaims().build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    @DisplayName("a token whose header is not JSON at all is a decode error")
    void tokenWithAnUnparseableHeader() {
      assertEquals(OAuthErrorCodes.DECODE_ERROR, codeOf(verifier(), "bm90LWpzb24.e30.c2ln"));
    }

    @Test
    void tokenWithoutSubject() {
      String token = tokens.signRs256(validClaims().subject(null).build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    void tokenWithoutExpiry() {
      String token = tokens.signRs256(validClaims().expirationTime(null).build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    void tokenWithoutIssuer() {
      TokenVerifier anyIssuer = new JwksVerifier("", "", tokens.publicJwkSource());
      String token = tokens.signRs256(validClaims().issuer(null).build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(anyIssuer, token));
    }

    @Test
    void tokenThatIsNotYetValid() {
      String token = tokens.signRs256(validClaims().notBeforeTime(secondsFromNow(3600)).build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }

    @Test
    @DisplayName("a not-before inside the clock-skew allowance is accepted")
    void notBeforeWithinClockSkew() {
      String token = tokens.signRs256(validClaims().notBeforeTime(secondsFromNow(60)).build());

      assertEquals("alice@example.com", verifier().verify(token).get("sub"));
    }
  }

  /**
   * A token with two defects at once must report the same code in every SDK, so the order the checks
   * run in is part of the contract rather than an implementation detail. The order is: required
   * claims, then lifetime, then issuer, then audience.
   */
  @Nested
  @DisplayName("checks claims in one fixed order, so a doubly-defective token has one answer")
  class ClaimCheckOrder {

    @Test
    @DisplayName("wrong issuer and wrong audience answers invalid_issuer")
    void issuerBeforeAudience() {
      String token = tokens.signRs256(validClaims()
          .issuer("https://wrong-issuer.example")
          .audience("not-us")
          .build());

      assertEquals(OAuthErrorCodes.INVALID_ISSUER, codeOf(verifierWithAudience("agents"), token));
    }

    @Test
    @DisplayName("expired and wrong issuer answers expired")
    void lifetimeBeforeIssuer() {
      String token = tokens.signRs256(validClaims()
          .issuer("https://wrong-issuer.example")
          .expirationTime(secondsFromNow(-3600))
          .build());

      assertEquals(OAuthErrorCodes.EXPIRED, codeOf(verifier(), token));
    }

    @Test
    @DisplayName("a missing required claim outranks an expired lifetime")
    void requiredClaimsBeforeLifetime() {
      String token = tokens.signRs256(
          validClaims().subject(null).expirationTime(secondsFromNow(-3600)).build());

      assertEquals(OAuthErrorCodes.INVALID_TOKEN, codeOf(verifier(), token));
    }
  }

  @Nested
  @DisplayName("answers 'not ready' rather than 'invalid' when")
  class NotReady {

    @Test
    @DisplayName("no published key matches the token's kid")
    void unknownKeyId() {
      String token = tokens.signRs256(validClaims().build(), TestTokens.OTHER_KEY_ID);

      assertThrows(VerifierNotReadyException.class, () -> verifier().verify(token));
    }

    @Test
    void theJwksEndpointIsUnreachable() {
      TokenVerifier unavailable =
          new JwksVerifier(TestTokens.ISSUER, "", TestTokens.unavailableJwkSource());
      String token = tokens.signRs256(validClaims().build());

      assertThrows(VerifierNotReadyException.class, () -> unavailable.verify(token));
    }

    @Test
    @DisplayName("warm-up against an unreachable endpoint does not throw")
    void warmSwallowsFailure() {
      new JwksVerifier(TestTokens.ISSUER, "", TestTokens.unavailableJwkSource()).warm();
    }
  }

  @Test
  @DisplayName("the returned claims cannot be mutated by the caller")
  void claimsAreUnmodifiable() {
    Map<String, Object> claims = verifier().verify(tokens.signRs256(validClaims().build()));

    assertThrows(UnsupportedOperationException.class, () -> claims.put("sub", "mallory@example.com"));
  }

  @Test
  @DisplayName("the algorithm allowlist is exactly RS256 and ES256")
  void algorithmAllowlist() {
    assertEquals(2, JwksVerifier.ALLOWED_ALGORITHMS.size());
    assertTrue(JwksVerifier.ALLOWED_ALGORITHMS.stream().map(Object::toString).toList().contains("RS256"));
    assertTrue(JwksVerifier.ALLOWED_ALGORITHMS.stream().map(Object::toString).toList().contains("ES256"));
  }

  @Test
  @DisplayName("the constants match the reference SDK")
  void constantsMatchTheReferenceSdk() {
    assertEquals(120, JwksVerifier.CLOCK_SKEW_SECONDS);
    assertEquals(300, JwksVerifier.JWKS_CACHE_LIFETIME_SECONDS);
  }
}
