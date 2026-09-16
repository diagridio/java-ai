package io.diagrid.springai.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.diagrid.ai.identity.JwksVerifier;
import io.diagrid.ai.identity.TokenVerifier;
import java.util.Date;

/**
 * A fixture RS256 keypair, so the filter is exercised against real signatures rather than a stubbed
 * verifier. That keeps the test honest about the thing most worth testing — that a request the filter
 * lets through really did carry a verified token.
 */
final class TestTokens {

  static final String KEY_ID = "test-key";
  static final String ISSUER = "https://oidc.example.com";

  private final RSAKey signingKey;

  private TestTokens(RSAKey signingKey) {
    this.signingKey = signingKey;
  }

  static TestTokens generate() {
    try {
      return new TestTokens(new RSAKeyGenerator(2048).keyID(KEY_ID).algorithm(JWSAlgorithm.RS256).generate());
    } catch (JOSEException e) {
      throw new IllegalStateException("could not generate the test keypair", e);
    }
  }

  TokenVerifier verifier() {
    return new JwksVerifier(ISSUER, "", new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK())));
  }

  /** A verifier whose key source always fails, standing in for an unreachable JWKS endpoint. */
  static TokenVerifier unavailableVerifier() {
    JWKSource<SecurityContext> failing = (selector, context) -> {
      throw new KeySourceException("JWKS endpoint unreachable");
    };
    return new JwksVerifier(ISSUER, "", failing);
  }

  static JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer(ISSUER)
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L));
  }

  String sign(JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
      jwt.sign(new RSASSASigner(signingKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign the test token", e);
    }
  }
}
