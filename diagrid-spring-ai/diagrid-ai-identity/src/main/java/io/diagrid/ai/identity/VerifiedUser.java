package io.diagrid.ai.identity;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The verified caller behind an inbound request.
 *
 * <p>Produced only from a token whose signature and claims have already been checked, so a handler
 * holding one of these can treat {@link #subject()} as trustworthy. {@link #claims()} carries the
 * whole decoded payload for policies the typed fields do not cover.
 *
 * @param subject  the {@code sub} claim — an email, a user id, or an agent SPIFFE URI
 * @param tenant   the tenant/org the caller belongs to, from {@code tid} when the token carries
 *                 that claim at all and from {@code tenant} otherwise; empty when it carries
 *                 neither
 * @param scopes   OAuth scopes carried by the token
 * @param claims   the full decoded JWT payload
 * @param issuerId the {@code iss} value on the verified token
 */
public record VerifiedUser(
    String subject,
    String tenant,
    Set<String> scopes,
    Map<String, Object> claims,
    String issuerId) {

  private static final String SUBJECT_CLAIM = "sub";
  private static final String ISSUER_CLAIM = "iss";
  private static final String TENANT_ID_CLAIM = "tid";
  private static final String TENANT_CLAIM = "tenant";

  /**
   * Claims that may carry scopes, in precedence order. Issuers disagree on which name to use, so
   * all three are read.
   */
  private static final List<String> SCOPE_CLAIMS = List.of("scp", "scope", "scopes");

  /** Splits the space-delimited encoding of the scope claim. */
  private static final String SCOPE_SEPARATOR = "\\s+";

  /**
   * Normalises {@code null} components to empty and defensively copies the collections, so a
   * {@code VerifiedUser} handed to application code cannot be mutated through the map it was built
   * from.
   */
  public VerifiedUser {
    subject = subject == null ? "" : subject;
    tenant = tenant == null ? "" : tenant;
    issuerId = issuerId == null ? "" : issuerId;
    // An ordinal TreeSet rather than Set.copyOf or LinkedHashSet: handlers echo scopes into JSON
    // bodies, and token order or Set.copyOf's per-run salt would make the same token yield a
    // different body.
    scopes = scopes == null
        ? Set.of()
        : Collections.unmodifiableSortedSet(new TreeSet<>(scopes));
    // unmodifiableMap over a fresh copy rather than Map.copyOf: a JWT payload may legally carry a
    // null-valued claim, which Map.copyOf rejects outright.
    claims = claims == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
  }

  /**
   * Whether the caller carries a particular scope.
   *
   * <p>A convenience over {@link #scopes()} for the per-route check a handler makes after the
   * adapter's blanket policy has passed. A {@code null} scope answers {@code false} rather than
   * throwing: a caller asking about nothing is not authorised for anything.
   *
   * @param scope the scope to look for
   * @return {@code true} when the verified token carries it
   */
  public boolean hasScope(String scope) {
    return scope != null && scopes.contains(scope);
  }

  /**
   * Builds a user from a verified token payload.
   *
   * <p>Lives here rather than in a framework adapter, so a Spring filter, a Quarkus filter and a
   * bare servlet all derive the same identity from the same token.
   *
   * @param claims the decoded payload returned by {@link TokenVerifier#verify(String)}
   * @return the caller the token represents
   */
  public static VerifiedUser fromClaims(Map<String, Object> claims) {
    Map<String, Object> payload = claims == null ? Map.of() : claims;
    // Presence, not emptiness, decides whether tenant is consulted: a token that carries tid at
    // all has already answered the question, unlike the scope claims above, where an empty value
    // falls through to the next name.
    String tenant = payload.containsKey(TENANT_ID_CLAIM)
        ? asString(payload.get(TENANT_ID_CLAIM))
        : asString(payload.get(TENANT_CLAIM));
    return new VerifiedUser(
        asString(payload.get(SUBJECT_CLAIM)),
        tenant,
        extractScopes(payload),
        payload,
        asString(payload.get(ISSUER_CLAIM)));
  }

  /**
   * Reads scopes from whichever of {@code scp}, {@code scope} or {@code scopes} the issuer used,
   * accepting both the JSON-array and the space-delimited-string encodings that are equally common in
   * the wild.
   *
   * @param payload the decoded token payload
   * @return the scopes carried by the token; empty when it carries none
   */
  public static Set<String> extractScopes(Map<String, Object> payload) {
    if (payload == null) {
      return Set.of();
    }
    for (String claim : SCOPE_CLAIMS) {
      Set<String> scopes = toScopeSet(payload.get(claim));
      if (!scopes.isEmpty()) {
        return scopes;
      }
    }
    return Set.of();
  }

  private static Set<String> toScopeSet(Object raw) {
    if (raw instanceof Collection<?> collection) {
      Set<String> scopes = new LinkedHashSet<>();
      for (Object element : collection) {
        String scope = asString(element);
        if (!scope.isEmpty()) {
          scopes.add(scope);
        }
      }
      return scopes;
    }
    if (raw instanceof String text && !text.isBlank()) {
      return new LinkedHashSet<>(List.of(text.trim().split(SCOPE_SEPARATOR)));
    }
    return Set.of();
  }

  private static String asString(Object value) {
    return value instanceof String text ? text : "";
  }
}
