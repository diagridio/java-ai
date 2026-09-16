package io.diagrid.ai.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * A hand-rolled RS256 keypair and token minting, so the verification tests need no live JWKS endpoint
 * and no identity provider. Every fail-closed case is produced by signing a real token and then
 * breaking exactly one thing about it.
 */
final class TestTokens {

  static final String KEY_ID = "test-key";
  static final String OTHER_KEY_ID = "other-key";
  static final String ISSUER = "https://oidc.example.com";

  private final RSAKey signingKey;

  private TestTokens(RSAKey signingKey) {
    this.signingKey = signingKey;
  }

  static TestTokens generate() {
    return generate(KEY_ID);
  }

  /** A keypair published under a specific key id, so a rotation can be staged. */
  static TestTokens generate(String keyId) {
    try {
      return new TestTokens(new RSAKeyGenerator(2048).keyID(keyId).algorithm(JWSAlgorithm.RS256).generate());
    } catch (JOSEException e) {
      throw new IllegalStateException("could not generate the test keypair", e);
    }
  }

  /** A key source publishing only this keypair's public half, as a real JWKS endpoint would. */
  JWKSource<SecurityContext> publicJwkSource() {
    return new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
  }

  /** The same public half as a JWKS document, for a test endpoint to serve. */
  String publicJwkSetJson() {
    return new JWKSet(signingKey.toPublicJWK()).toString();
  }

  /** A key source that always fails, standing in for an unreachable JWKS endpoint. */
  static JWKSource<SecurityContext> unavailableJwkSource() {
    return (selector, context) -> {
      throw new KeySourceException("JWKS endpoint unreachable");
    };
  }

  String signRs256(JWTClaimsSet claims) {
    return signRs256(claims, signingKey.getKeyID());
  }

  String signRs256(JWTClaimsSet claims, String keyId) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
      jwt.sign(new RSASSASigner(signingKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign the test token", e);
    }
  }

  /** Signs with a throwaway key while claiming the trusted key's id, so the signature cannot verify. */
  static String signWithWrongKey(JWTClaimsSet claims) {
    return generate().signRs256(claims, KEY_ID);
  }

  /** An HMAC-signed token: the algorithm-confusion attempt the allowlist exists to stop. */
  static String signHs256(JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), claims);
      jwt.sign(new MACSigner("a-secret-long-enough-for-hmac-sha256-signing".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign the test token", e);
    }
  }

  /** An unsecured {@code alg: none} token. */
  static String unsecured(JWTClaimsSet claims) {
    return new PlainJWT(claims).serialize();
  }

  /**
   * A token carrying {@code header} verbatim, for header shapes no JOSE builder will produce.
   *
   * <p>The signature segment is nonsense on purpose: nothing should reach a signature check with a
   * header like this one.
   *
   * @param header the raw JSON of the JOSE header
   * @param claims the payload
   * @return the serialised token
   */
  static String withRawHeader(String header, JWTClaimsSet claims) {
    return Base64URL.encode(header) + "." + Base64URL.encode(claims.toString()) + ".c2lnbmF0dXJl";
  }
}
