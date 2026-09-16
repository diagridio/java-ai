package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IdentityTypesTest {

  @Nested
  class Config {

    @Test
    @DisplayName("defaults to requiring a verified caller with no particular scope")
    void defaults() {
      OAuthConfig config = new OAuthConfig();

      assertTrue(config.scopes().isEmpty());
      assertTrue(config.requireAuth());
      assertFalse(config.allowInsecureJwks());
      assertEquals(null, config.issuer());
      assertEquals(null, config.audience());
      assertEquals(null, config.jwksUri());
    }

    @Test
    @DisplayName("plaintext JWKS is opt-in, so every constructor that omits it stays fail-closed")
    void insecureJwksIsOptIn() {
      assertFalse(new OAuthConfig(Set.of("agent.invoke")).allowInsecureJwks());
      assertFalse(new OAuthConfig(Set.of(), null, null, null, false).allowInsecureJwks());
      assertTrue(new OAuthConfig(Set.of(), null, null, null, true, true).allowInsecureJwks());
    }

    @Test
    void theScopeOnlyConstructorDiscoversEverythingElse() {
      OAuthConfig config = new OAuthConfig(Set.of("agent.invoke"));

      assertEquals(Set.of("agent.invoke"), config.scopes());
      assertTrue(config.requireAuth());
      assertEquals(null, config.issuer());
    }

    @Test
    @DisplayName("mutating the set it was built from does not change the policy")
    void scopesAreCopied() {
      Set<String> scopes = new HashSet<>(Set.of("agent.invoke"));
      OAuthConfig config = new OAuthConfig(scopes);
      scopes.add("admin.write");

      assertEquals(Set.of("agent.invoke"), config.scopes());
      assertThrows(UnsupportedOperationException.class, () -> config.scopes().add("x"));
    }

    @Test
    void nullScopesMeanNoScopeRequirement() {
      assertTrue(new OAuthConfig(null, null, null, null, true).scopes().isEmpty());
    }

    @Test
    void reportsTheScopesACallerIsMissing() {
      OAuthConfig config = new OAuthConfig(Set.of("agent.invoke", "admin.write"));

      assertEquals(Set.of("admin.write"), config.missingScopes(Set.of("agent.invoke")));
      assertTrue(config.missingScopes(Set.of("agent.invoke", "admin.write", "extra")).isEmpty());
      assertEquals(Set.of("agent.invoke", "admin.write"), config.missingScopes(Set.of()));
      assertEquals(Set.of("agent.invoke", "admin.write"), config.missingScopes(null));
    }

    @Test
    @DisplayName("an empty policy authorises any verified caller")
    void noRequiredScopes() {
      assertTrue(new OAuthConfig().missingScopes(Set.of()).isEmpty());
    }

    @Test
    @DisplayName("scopes and missingScopes iterate ordinally sorted, stably across JVM runs")
    void scopesIterateSorted() {
      // Set.copyOf salts its iteration order per JVM run, so the same app would report the same
      // missing scopes in a different order on every start.
      OAuthConfig config = new OAuthConfig(new LinkedHashSet<>(List.of("zeta", "alpha", "mu")));

      assertEquals(List.of("alpha", "mu", "zeta"), List.copyOf(config.scopes()));
      assertEquals(List.of("alpha", "zeta"), List.copyOf(config.missingScopes(Set.of("mu"))));
    }
  }

  @Nested
  class User {

    @Test
    void readsTheTypedFieldsFromTheClaims() {
      VerifiedUser user = VerifiedUser.fromClaims(Map.of(
          "sub", "alice@example.com",
          "tid", "acme-corp",
          "iss", "https://oidc.example.com",
          "scp", List.of("agent.invoke", "admin")));

      assertEquals("alice@example.com", user.subject());
      assertEquals("acme-corp", user.tenant());
      assertEquals("https://oidc.example.com", user.issuerId());
      assertEquals(Set.of("agent.invoke", "admin"), user.scopes());
    }

    @Test
    @DisplayName("falls back to the tenant claim when tid is absent")
    void tenantFallback() {
      assertEquals("acme", VerifiedUser.fromClaims(Map.of("tenant", "acme")).tenant());
    }

    @Test
    @DisplayName("tid wins over tenant when both are present")
    void tenantPrecedence() {
      assertEquals("from-tid", VerifiedUser.fromClaims(Map.of("tid", "from-tid", "tenant", "other")).tenant());
    }

    @Test
    @DisplayName("an empty tid is the answer, not a reason to read tenant")
    void emptyTenantIdDoesNotFallThrough() {
      // Presence, not emptiness: a token that says "no tenant" in tid means it, unlike the scope
      // claims, where an empty value falls through to the next name.
      assertEquals("", VerifiedUser.fromClaims(Map.of("tid", "", "tenant", "acme")).tenant());
    }

    @Test
    void readsScopesFromTheSpaceDelimitedStringEncoding() {
      assertEquals(Set.of("read", "write"), VerifiedUser.fromClaims(Map.of("scope", "read write")).scopes());
    }

    @Test
    @DisplayName("reads scopes from whichever claim the issuer used")
    void scopeClaimAliases() {
      assertEquals(Set.of("a"), VerifiedUser.fromClaims(Map.of("scp", List.of("a"))).scopes());
      assertEquals(Set.of("b"), VerifiedUser.fromClaims(Map.of("scope", List.of("b"))).scopes());
      assertEquals(Set.of("c"), VerifiedUser.fromClaims(Map.of("scopes", List.of("c"))).scopes());
    }

    @Test
    @DisplayName("scp wins over scope, which wins over scopes")
    void scopeClaimPrecedence() {
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("scp", List.of("from-scp"));
      claims.put("scope", "from-scope");
      claims.put("scopes", List.of("from-scopes"));

      assertEquals(Set.of("from-scp"), VerifiedUser.fromClaims(claims).scopes());
    }

    @Test
    void aTokenWithNoScopeClaimCarriesNoScopes() {
      assertTrue(VerifiedUser.fromClaims(Map.of("sub", "alice")).scopes().isEmpty());
      assertTrue(VerifiedUser.fromClaims(Map.of("scope", "")).scopes().isEmpty());
      assertTrue(VerifiedUser.fromClaims(Map.of()).scopes().isEmpty());
      assertTrue(VerifiedUser.fromClaims(null).scopes().isEmpty());
    }

    @Test
    @DisplayName("missing string claims read as empty, never null")
    void missingClaimsAreEmpty() {
      VerifiedUser user = VerifiedUser.fromClaims(Map.of());

      assertEquals("", user.subject());
      assertEquals("", user.tenant());
      assertEquals("", user.issuerId());
    }

    @Test
    @DisplayName("a null-valued claim does not break construction")
    void nullValuedClaim() {
      Map<String, Object> claims = new HashMap<>();
      claims.put("sub", "alice");
      claims.put("optional", null);

      assertEquals("alice", VerifiedUser.fromClaims(claims).subject());
    }

    @Test
    @DisplayName("answers whether the caller carries a given scope")
    void hasScope() {
      VerifiedUser user = VerifiedUser.fromClaims(Map.of("scp", List.of("agent.invoke", "admin")));

      assertTrue(user.hasScope("agent.invoke"));
      assertTrue(user.hasScope("admin"));
      assertFalse(user.hasScope("admin.write"));
      assertFalse(user.hasScope(""));
      assertFalse(user.hasScope(null));
      assertFalse(VerifiedUser.fromClaims(Map.of()).hasScope("agent.invoke"));
    }

    @Test
    @DisplayName("scopes iterate ordinally sorted, whatever order the token listed them in")
    void scopesIterateSorted() {
      List<String> declared = List.of("zeta", "alpha", "mu", "beta");
      List<String> sorted = List.of("alpha", "beta", "mu", "zeta");

      // Handlers echo scopes into JSON bodies, so token order would make the same token yield a
      // different body.
      assertEquals(sorted, List.copyOf(VerifiedUser.fromClaims(Map.of("scp", declared)).scopes()));
      assertEquals(
          sorted,
          List.copyOf(VerifiedUser.fromClaims(Map.of("scope", "zeta alpha mu beta")).scopes()));
      assertEquals(
          sorted,
          List.copyOf(
              new VerifiedUser("alice", "", new LinkedHashSet<>(declared), Map.of(), "iss").scopes()));
    }

    @Test
    @DisplayName("a token listing the same scope twice carries it once")
    void duplicateScopesAreCollapsed() {
      assertEquals(
          List.of("a", "b"),
          List.copyOf(VerifiedUser.fromClaims(Map.of("scope", "b a b")).scopes()));
    }

    @Test
    @DisplayName("the caller cannot mutate the identity it was handed")
    void isImmutable() {
      VerifiedUser user = VerifiedUser.fromClaims(Map.of("sub", "alice", "scp", List.of("a")));

      assertThrows(UnsupportedOperationException.class, () -> user.claims().put("sub", "mallory"));
      assertThrows(UnsupportedOperationException.class, () -> user.scopes().add("admin"));
    }
  }

  @Test
  @DisplayName("the error body is the one shape every Diagrid SDK writes")
  void errorBodyShape() {
    assertEquals(
        "{\"error\":\"oauth.missing_token\"}",
        new OAuthErrorBody(OAuthErrorCodes.MISSING_TOKEN).toJson());
    assertThrows(NullPointerException.class, () -> new OAuthErrorBody(null));
  }

  @Test
  @DisplayName("the error codes match the reference SDK verbatim")
  void errorCodesMatchTheReferenceSdk() {
    assertEquals("oauth.missing_token", OAuthErrorCodes.MISSING_TOKEN);
    assertEquals("oauth.not_configured", OAuthErrorCodes.NOT_CONFIGURED);
    assertEquals("oauth.verifier_unavailable", OAuthErrorCodes.VERIFIER_UNAVAILABLE);
    assertEquals("oauth.expired", OAuthErrorCodes.EXPIRED);
    assertEquals("oauth.invalid_issuer", OAuthErrorCodes.INVALID_ISSUER);
    assertEquals("oauth.invalid_audience", OAuthErrorCodes.INVALID_AUDIENCE);
    assertEquals("oauth.invalid_signature", OAuthErrorCodes.INVALID_SIGNATURE);
    assertEquals("oauth.decode_error", OAuthErrorCodes.DECODE_ERROR);
    assertEquals("oauth.invalid_token", OAuthErrorCodes.INVALID_TOKEN);
    assertEquals("oauth.missing_scope", OAuthErrorCodes.MISSING_SCOPE);
  }
}
