package io.diagrid.ai.identity;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The policy an inbound-identity adapter enforces on every request.
 *
 * <p>Every field except {@code scopes} is normally left unset: the issuer, audience and JWKS URI are
 * discovered from the Catalyst sidecar's {@code /v1.0/metadata} endpoint at first use, so an app that
 * moves between projects or regions needs no configuration change. Set them explicitly only when the
 * metadata endpoint is unreachable — doing so pins the app to one issuer.
 *
 * <p>The common case is a single argument:
 *
 * <pre>{@code
 * new OAuthConfig(Set.of("agent.invoke"))
 * }</pre>
 *
 * @param scopes      scopes the caller's token must carry; a token missing any of them is rejected
 *                    with {@link OAuthErrorCodes#MISSING_SCOPE}. Empty means "any verified caller"
 * @param issuer      expected {@code iss} claim, or {@code null} to discover it
 * @param audience    expected {@code aud} claim, or {@code null} to discover it. When it resolves
 *                    to empty the audience is not checked at all
 * @param jwksUri     JWKS endpoint used for signature verification, or {@code null} to discover it
 * @param requireAuth when {@code true} (the default), a request with no user token is rejected with
 *                    {@link OAuthErrorCodes#MISSING_TOKEN}. Set {@code false} when unauthenticated
 *                    routes — health, readiness — share the app, and let the handler decide
 * @param allowInsecureJwks when {@code true}, the resolved JWKS endpoint may be plain {@code http}
 *                    on a host that is not loopback. Off by default, and worth leaving off: the
 *                    published key set is the whole root of trust, so an on-path attacker who can
 *                    rewrite a plaintext response mints tokens this policy accepts. Loopback needs
 *                    no opt-in — the local sidecar serves keys over plain http already
 */
public record OAuthConfig(
    Set<String> scopes,
    String issuer,
    String audience,
    String jwksUri,
    boolean requireAuth,
    boolean allowInsecureJwks) {

  /**
   * Normalises {@code null} scopes to empty and defensively copies into an ordinal
   * {@link TreeSet}, so the record is deeply immutable and callers can pass a mutable set without
   * the config changing underneath the filter.
   *
   * <p>Sorted rather than {@code Set.copyOf}, which salts its iteration order per JVM run: the same
   * app would otherwise report {@link #missingScopes(Set)} in a different order on every start.
   */
  public OAuthConfig {
    scopes = scopes == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(scopes));
  }

  /**
   * The policy without the plaintext-JWKS opt-in, which stays off.
   *
   * @param scopes      scopes every caller must carry
   * @param issuer      expected {@code iss} claim, or {@code null} to discover it
   * @param audience    expected {@code aud} claim, or {@code null} to discover it
   * @param jwksUri     JWKS endpoint, or {@code null} to discover it
   * @param requireAuth whether a request with no user token is rejected
   */
  public OAuthConfig(
      Set<String> scopes, String issuer, String audience, String jwksUri, boolean requireAuth) {
    this(scopes, issuer, audience, jwksUri, requireAuth, false);
  }

  /**
   * Requires the given scopes and discovers everything else.
   *
   * @param scopes scopes every caller must carry
   */
  public OAuthConfig(Set<String> scopes) {
    this(scopes, null, null, null, true, false);
  }

  /** Requires a verified caller, with no scope requirement, and discovers everything else. */
  public OAuthConfig() {
    this(Set.of(), null, null, null, true, false);
  }

  /**
   * Returns the required scopes the caller does not have.
   *
   * <p>An empty result means the caller is authorised. The whole authorisation decision lives here
   * rather than in a framework adapter, so every adapter reaches it identically.
   *
   * @param granted scopes carried by the verified token
   * @return the missing scopes; empty when the caller satisfies the policy
   */
  public Set<String> missingScopes(Set<String> granted) {
    Set<String> missing = new TreeSet<>(scopes);
    if (granted != null) {
      missing.removeAll(granted);
    }
    return missing;
  }
}
