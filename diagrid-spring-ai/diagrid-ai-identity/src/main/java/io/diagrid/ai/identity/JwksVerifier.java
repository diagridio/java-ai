package io.diagrid.ai.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObject;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The {@link TokenVerifier} this module ships: verifies the JWTs Diagrid issues for end users
 * against keys published at a JWKS endpoint.
 *
 * <p>One instance serves the whole application: it is thread-safe, and the key set it fetches is
 * cached and refreshed in the background, so steady-state verification does no network I/O.
 *
 * <p>{@link #verify(String)} checks the signature with Nimbus and then the claims by hand, rather
 * than handing both to a {@code DefaultJWTClaimsVerifier}, which collapses every claim failure into
 * one exception type. A caller must be able to tell an expired token from one meant for another
 * audience, and those codes are a cross-SDK contract ({@link OAuthErrorCodes}).
 *
 * <p>Verification is fail-closed throughout: anything that is not a positively verified token
 * raises, and no path returns a partially checked result. A JWKS endpoint that is not https, not
 * loopback and not explicitly opted in to is refused while the verifier is being built rather than
 * on the first request that needs a key (see {@link #requireSecureTransport(URI, boolean)}).
 */
public final class JwksVerifier implements TokenVerifier {

  /**
   * Tolerance for clock drift between the issuer and this host, applied to {@code exp} and
   * {@code nbf}. Shared with the other Diagrid SDKs, so changing it here alone would make them
   * disagree on which tokens are live.
   */
  static final int CLOCK_SKEW_SECONDS = 120;

  /** How long a fetched key set is served before it is refreshed. */
  static final int JWKS_CACHE_LIFETIME_SECONDS = 300;

  /**
   * The only signature algorithms accepted. An allowlist, not a denylist: a token asking to be
   * verified with {@code HS256} or served as an unsecured JWT is rejected before any key is
   * consulted, which is what closes the algorithm-confusion attack.
   */
  static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

  /**
   * The RFC 9068 access-token type dp-Sentry stamps on the user token.
   *
   * <p>Nimbus's default type verifier accepts only {@code JWT} or an absent {@code typ}, and
   * rejects anything else before key selection runs. Without this the real Catalyst credential
   * failed as "no published signing key matches the token" while its key matched perfectly.
   */
  private static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

  private static final String MISSING_COORDINATES_MESSAGE =
      "Cannot discover identity coordinates: set issuer/jwks_uri explicitly, "
          + "configure the sidecar metadata endpoint (" + IdentityDiscovery.DAPR_HTTP_PORT_ENV
          + " locally or " + IdentityDiscovery.DAPR_HTTP_ENDPOINT_ENV + " for a remote sidecar), "
          + "or set " + IdentityDiscovery.ISSUER_ENV;

  private static final String ALGORITHM_HEADER = "alg";

  private static final String ABSENT_ALGORITHM = "(absent)";

  private static final String UNUSABLE_JWKS_URI_MESSAGE = "Not a usable JWKS URI: ";

  private static final String HTTPS_SCHEME = "https";

  private static final String HTTP_SCHEME = "http";

  /**
   * Host names that name this machine. Matched literally rather than resolved: asking DNS what a
   * name points at would let whoever controls the name decide whether the plaintext rule applies.
   */
  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "::1", "0:0:0:0:0:0:0:1");

  /**
   * Every address in 127.0.0.0/8 is loopback, not only 127.0.0.1. Anchored by {@code matches}, so a
   * host that merely begins with those digits — {@code 127.0.0.1.attacker.example} — does not match.
   */
  private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(\\.\\d{1,3}){3}");

  private static final long MILLIS_PER_SECOND = 1000L;

  private static final Logger LOGGER = System.getLogger(JwksVerifier.class.getName());

  private final String issuer;
  private final String audience;
  private final String jwksUri;
  private final boolean allowInsecureJwks;

  /**
   * The key source and the processor built around it, published as one unit. A CAS rather than a lock
   * so a burst of first requests never queues behind a single builder; the loser of the race drops
   * its own instance, which costs nothing because building does no I/O.
   */
  private final AtomicReference<Verification> verification = new AtomicReference<>();

  /**
   * Verifies against the keys published at a JWKS endpoint, which must be https or loopback.
   *
   * @param issuer   expected {@code iss} claim; empty to skip the issuer check
   * @param audience expected {@code aud} claim; empty to skip the audience check
   * @param jwksUri  endpoint publishing the issuer's signing keys
   */
  public JwksVerifier(String issuer, String audience, String jwksUri) {
    this(issuer, audience, jwksUri, false);
  }

  /**
   * Verifies against the keys published at a JWKS endpoint, with the transport rule spelled out.
   *
   * @param issuer            expected {@code iss} claim; empty to skip the issuer check
   * @param audience          expected {@code aud} claim; empty to skip the audience check
   * @param jwksUri           endpoint publishing the issuer's signing keys
   * @param allowInsecureJwks whether a plain {@code http} endpoint on a non-loopback host is
   *                          accepted; see {@link #requireSecureTransport(URI, boolean)}
   */
  public JwksVerifier(String issuer, String audience, String jwksUri, boolean allowInsecureJwks) {
    this.issuer = orEmpty(issuer);
    this.audience = orEmpty(audience);
    this.jwksUri = orEmpty(jwksUri);
    this.allowInsecureJwks = allowInsecureJwks;
    // Refused here rather than on the first key fetch: warm() swallows fetch failures, so an
    // endpoint that could never work would otherwise leave the app up and answering 503 to every
    // request.
    requireSecureTransport(jwksEndpoint(this.jwksUri), allowInsecureJwks);
  }

  /**
   * Verifies against a caller-supplied key source.
   *
   * <p>For tests with a fixture key set, and for deployments that retrieve keys some way this module
   * does not know about.
   *
   * @param issuer    expected {@code iss} claim; empty to skip the issuer check
   * @param audience  expected {@code aud} claim; empty to skip the audience check
   * @param jwkSource the signing keys to verify against
   */
  public JwksVerifier(String issuer, String audience, JWKSource<SecurityContext> jwkSource) {
    this.issuer = orEmpty(issuer);
    this.audience = orEmpty(audience);
    this.jwksUri = "";
    // No endpoint is resolved, so there is no transport to rule on.
    this.allowInsecureJwks = false;
    this.verification.set(new Verification(jwkSource, processorFor(jwkSource)));
  }

  /**
   * Builds a verifier for the given policy, resolving whatever the policy left unset.
   *
   * <p>Precedence is explicit configuration, then the local sidecar's metadata endpoint, then a
   * remote sidecar's, then the environment. Local before remote keeps an app deployed alongside a
   * sidecar answering from loopback rather than paying for a network round trip.
   *
   * <p>The JWKS endpoint resolves in the same spirit: an explicit {@code jwksUri}, else the one a
   * source advertised <em>for the issuer that was resolved</em>, else {@code issuer + /jwks.json}.
   * Deriving before consulting the advertised value would ignore a sidecar that publishes its keys
   * away from its issuer; adopting it without the issuer check would point a pinned issuer at a
   * foreign issuer's keys.
   *
   * @param config the policy; its {@code scopes} and {@code requireAuth} are not used here
   * @return a warmed verifier
   * @throws IdentityNotConfiguredException when no source names an issuer, or when the endpoint
   *     they name could never serve keys — an unusable URI, or plaintext without the opt-in
   */
  public static JwksVerifier build(OAuthConfig config) {
    return build(config, System::getenv);
  }

  static JwksVerifier build(OAuthConfig config, UnaryOperator<String> env) {
    OAuthConfig policy = config == null ? new OAuthConfig() : config;
    IdentityCoordinates discovered = null;
    if (orEmpty(policy.issuer()).isEmpty() || orEmpty(policy.jwksUri()).isEmpty()) {
      discovered = IdentityDiscovery.fromMetadata(env);
      if (discovered == null) {
        discovered = IdentityDiscovery.fromRemote(env);
      }
      if (discovered == null) {
        discovered = IdentityDiscovery.fromEnvironment(env);
      }
    }

    String resolvedIssuer = firstNonEmpty(policy.issuer(), discovered == null ? "" : discovered.issuer());
    String resolvedJwksUri = orEmpty(policy.jwksUri());
    // The advertised key set is adopted only for the issuer that advertised it: a sidecar naming a
    // different issuer is describing someone else's keys.
    if (resolvedJwksUri.isEmpty() && discovered != null && resolvedIssuer.equals(discovered.issuer())) {
      resolvedJwksUri = orEmpty(discovered.jwksUri());
    }
    if (resolvedJwksUri.isEmpty() && !resolvedIssuer.isEmpty()) {
      resolvedJwksUri = IdentityDiscovery.defaultJwksUri(resolvedIssuer);
    }
    String resolvedAudience = firstNonEmpty(policy.audience(), discovered == null ? "" : discovered.audience());

    if (resolvedIssuer.isEmpty() || resolvedJwksUri.isEmpty()) {
      throw new IdentityNotConfiguredException(MISSING_COORDINATES_MESSAGE);
    }

    JwksVerifier verifier =
        new JwksVerifier(resolvedIssuer, resolvedAudience, resolvedJwksUri, policy.allowInsecureJwks());
    verifier.warm();
    return verifier;
  }

  String issuer() {
    return issuer;
  }

  String audience() {
    return audience;
  }

  String jwksUri() {
    return jwksUri;
  }

  /**
   * Fetches the key set now, so the first real request does not pay for it.
   *
   * <p>Failures are logged and swallowed: a JWKS endpoint slow to come up must not stop the app
   * from starting, and the fetch is retried on the first request. Nothing is verified against a
   * missing key set in the meantime — requests get a 503 until keys arrive.
   */
  public void warm() {
    try {
      JWKSource<SecurityContext> source = verification().jwkSource();
      source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "JWKS warm-up failed; will retry on the first request", e);
    }
  }

  @Override
  public Map<String, Object> verify(String rawToken) {
    // The allowlist is applied to the header alone, before anything parses the token as a signed
    // JWT: an unsecured alg:none token has no signature segment, so deciding the algorithm later
    // would report it as unparseable rather than as the rejected algorithm it is.
    JWSAlgorithm algorithm = declaredAlgorithm(orEmpty(rawToken));
    if (algorithm == null || !ALLOWED_ALGORITHMS.contains(algorithm)) {
      throw new TokenVerificationException(
          OAuthErrorCodes.INVALID_TOKEN,
          "signature algorithm " + (algorithm == null ? ABSENT_ALGORITHM : algorithm.getName())
              + " is not allowed");
    }

    SignedJWT signedJwt;
    try {
      signedJwt = SignedJWT.parse(orEmpty(rawToken));
    } catch (ParseException e) {
      throw new TokenVerificationException(
          OAuthErrorCodes.DECODE_ERROR, "token is not a well-formed signed JWT", e);
    }

    JWTClaimsSet claims;
    try {
      claims = verification().processor().process(signedJwt, null);
    } catch (BadJWSException e) {
      throw new TokenVerificationException(
          OAuthErrorCodes.INVALID_SIGNATURE, "signature verification failed", e);
    } catch (BadJOSEException e) {
      // Nimbus reports "no key matched" this way; the credential may be fine and our key set stale.
      throw new VerifierNotReadyException("no published signing key matches the token", e);
    } catch (KeySourceException e) {
      throw new VerifierNotReadyException("JWKS retrieval failed", e);
    } catch (JOSEException e) {
      throw new VerifierNotReadyException("the token could not be verified", e);
    }

    validateClaims(claims);
    return Collections.unmodifiableMap(new LinkedHashMap<>(claims.toJSONObject()));
  }

  /**
   * Checks the claims in the order every Diagrid SDK checks them, so a token with more than one
   * defect yields the same error code whichever SDK served the request.
   *
   * <p>That order is required claims present, then {@code nbf}/{@code exp}, then {@code iss}, then
   * {@code aud}. Nothing in the code forces the blocks to stay in it, so tests pin it.
   *
   * @param claims the claims of an already signature-verified token
   */
  private void validateClaims(JWTClaimsSet claims) {
    Date expiration = claims.getExpirationTime();
    requirePresent(expiration, "exp");
    requirePresent(emptyToNull(claims.getIssuer()), "iss");
    requirePresent(emptyToNull(claims.getSubject()), "sub");

    Instant now = Instant.now();
    Date notBefore = claims.getNotBeforeTime();
    if (notBefore != null && now.isBefore(toInstant(notBefore).minusSeconds(CLOCK_SKEW_SECONDS))) {
      throw new TokenVerificationException(OAuthErrorCodes.INVALID_TOKEN, "token is not yet valid");
    }
    if (now.isAfter(toInstant(expiration).plusSeconds(CLOCK_SKEW_SECONDS))) {
      throw new TokenVerificationException(OAuthErrorCodes.EXPIRED, "token has expired");
    }
    if (!issuer.isEmpty() && !issuer.equals(claims.getIssuer())) {
      throw new TokenVerificationException(OAuthErrorCodes.INVALID_ISSUER, "issuer mismatch");
    }
    // An unconfigured audience means "do not check": tokens are scoped by issuer and scope, and
    // demanding an audience nobody sets would reject every valid one.
    if (!audience.isEmpty() && !claims.getAudience().contains(audience)) {
      throw new TokenVerificationException(OAuthErrorCodes.INVALID_AUDIENCE, "audience mismatch");
    }
  }

  /**
   * The algorithm the token's own header names, read without interpreting the rest of the token.
   *
   * @param rawToken the compact serialisation
   * @return the declared algorithm, or {@code null} when the header names none
   * @throws TokenVerificationException with {@link OAuthErrorCodes#DECODE_ERROR} when the token is
   *     not three Base64URL segments whose first is a JSON object
   */
  private static JWSAlgorithm declaredAlgorithm(String rawToken) {
    try {
      Base64URL[] segments = JOSEObject.split(rawToken);
      Object declared = JSONObjectUtils.parse(segments[0].decodeToString()).get(ALGORITHM_HEADER);
      return declared instanceof String name ? JWSAlgorithm.parse(name) : null;
    } catch (ParseException e) {
      throw new TokenVerificationException(
          OAuthErrorCodes.DECODE_ERROR, "token is not a well-formed JWT", e);
    }
  }

  private static void requirePresent(Object claim, String name) {
    if (claim == null) {
      throw new TokenVerificationException(
          OAuthErrorCodes.INVALID_TOKEN, "token is missing the required claim: " + name);
    }
  }

  private Verification verification() {
    Verification current = verification.get();
    if (current != null) {
      return current;
    }
    JWKSource<SecurityContext> source = remoteJwkSource();
    Verification built = new Verification(source, processorFor(source));
    return verification.compareAndSet(null, built) ? built : verification.get();
  }

  private JWKSource<SecurityContext> remoteJwkSource() {
    // Already settled in the constructor; repeated so any future path into this method is safe too.
    URI uri = jwksEndpoint(jwksUri);
    requireSecureTransport(uri, allowInsecureJwks);
    try {
      return JWKSourceBuilder.<SecurityContext>create(uri.toURL())
          .cache(JWKS_CACHE_LIFETIME_SECONDS * MILLIS_PER_SECOND, JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
          .build();
    } catch (MalformedURLException | IllegalArgumentException e) {
      throw new IdentityNotConfiguredException(UNUSABLE_JWKS_URI_MESSAGE + jwksUri);
    }
  }

  /** Parses the resolved endpoint, reporting an unparseable one as a configuration failure. */
  private static URI jwksEndpoint(String jwksUri) {
    try {
      return URI.create(jwksUri);
    } catch (IllegalArgumentException e) {
      throw new IdentityNotConfiguredException(UNUSABLE_JWKS_URI_MESSAGE + jwksUri);
    }
  }

  /**
   * Refuses a JWKS endpoint whose keys would be fetched in the clear.
   *
   * <p>The published key set is the whole root of trust: anyone who can rewrite that response mints
   * tokens this verifier accepts. The endpoint is not always the app's own choice — it can arrive
   * from the environment or from the sidecar's metadata — so its transport is checked, not assumed.
   *
   * <p>Loopback needs no opt-in, and its host is matched literally rather than resolved, so a name
   * an attacker controls cannot claim to be loopback. {@code allowInsecureJwks} widens the rule to
   * plain {@code http} anywhere and no further: any other scheme is refused whatever the flag
   * says.
   *
   * @param uri               the resolved JWKS endpoint
   * @param allowInsecureJwks whether plain {@code http} is accepted on a non-loopback host
   * @throws IdentityNotConfiguredException when the endpoint is not https, not loopback and not
   *     opted in to
   */
  static void requireSecureTransport(URI uri, boolean allowInsecureJwks) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (HTTPS_SCHEME.equals(scheme)) {
      return;
    }
    if (HTTP_SCHEME.equals(scheme) && (allowInsecureJwks || isLoopback(uri.getHost()))) {
      return;
    }
    throw new IdentityNotConfiguredException(
        "Refusing to fetch signing keys over an insecure transport; the JWKS endpoint must be https "
            + "(plain http is allowed for loopback, or set allowInsecureJwks): " + uri);
  }

  private static boolean isLoopback(String host) {
    if (host == null) {
      return false;
    }
    String bare = host.toLowerCase(Locale.ROOT);
    if (bare.startsWith("[") && bare.endsWith("]")) {
      bare = bare.substring(1, bare.length() - 1);
    }
    return LOOPBACK_HOSTS.contains(bare) || IPV4_LOOPBACK.matcher(bare).matches();
  }

  private static ConfigurableJWTProcessor<SecurityContext> processorFor(JWKSource<SecurityContext> source) {
    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, source));
    // Accept `JWT`, `at+jwt` and an absent `typ`, matching what the other SDKs accept.
    processor.setJWSTypeVerifier(
        new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, AT_JWT, null));
    // Claims are checked in validateClaims, which can name the exact failure; the library's verifier
    // cannot. Leaving this unset would silently re-enable its default expiry check.
    processor.setJWTClaimsSetVerifier((claims, context) -> { });
    return processor;
  }

  private static Instant toInstant(Date date) {
    return Instant.ofEpochMilli(date.getTime());
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String firstNonEmpty(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isEmpty()) {
        return candidate;
      }
    }
    return "";
  }

  /** The key source and the JWT processor built around it, swapped in together or not at all. */
  private record Verification(
      JWKSource<SecurityContext> jwkSource, ConfigurableJWTProcessor<SecurityContext> processor) {
  }
}
