package io.diagrid.springai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.ai.identity.IdentityContext;
import io.diagrid.ai.identity.IdentityNotConfiguredException;
import io.diagrid.ai.identity.OAuthConfig;
import io.diagrid.ai.identity.TokenVerifier;
import io.diagrid.ai.identity.VerifiedUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter's whole job is turning a verification outcome into HTTP, so every case here asserts the
 * status, the body and whether the request reached the handler. The status/code pairs are a cross-SDK
 * contract; a caller must get the same answer from this filter as from any other Diagrid SDK.
 */
class OAuthFilterTest {

  private static TestTokens tokens;

  @BeforeAll
  static void generateKeypair() {
    tokens = TestTokens.generate();
  }

  @AfterEach
  void clearToken() {
    IdentityContext.clearCurrentToken();
  }

  private static MockHttpServletRequest requestWithToken(String headerValue) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke");
    if (headerValue != null) {
      request.addHeader(IdentityContext.USER_TOKEN_HEADER, headerValue);
    }
    return request;
  }

  /** Runs the filter and reports whether the request got through to the handler. */
  private static boolean doFilter(
      OAuthFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
      throws ServletException, IOException {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return chain.getRequest() != null;
  }

  private static String bodyOf(MockHttpServletResponse response) throws IOException {
    return response.getContentAsString();
  }

  @Nested
  @DisplayName("lets a request through when")
  class Accepts {

    @Test
    void theTokenVerifiesAndCarriesTheRequiredScopes() throws Exception {
      OAuthFilter filter =
          new OAuthFilter(new OAuthConfig(Set.of("agent.invoke")), tokens.verifier());
      String token = tokens.sign(TestTokens.validClaims()
          .claim("tid", "acme-corp")
          .claim("scp", List.of("agent.invoke", "admin"))
          .build());
      MockHttpServletRequest request = requestWithToken("Bearer " + token);
      MockHttpServletResponse response = new MockHttpServletResponse();

      assertTrue(doFilter(filter, request, response));

      assertEquals(HttpServletResponse.SC_OK, response.getStatus());
      VerifiedUser user = OAuthFilter.verifiedUser(request).orElseThrow();
      assertEquals("alice@example.com", user.subject());
      assertEquals("acme-corp", user.tenant());
      assertEquals(Set.of("agent.invoke", "admin"), user.scopes());
      assertEquals(TestTokens.ISSUER, user.issuerId());
    }

    @Test
    @DisplayName("scopes arrive as a space-delimited string")
    void spaceDelimitedScopes() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(Set.of("read")), tokens.verifier());
      String token = tokens.sign(TestTokens.validClaims().claim("scope", "read write").build());
      MockHttpServletRequest request = requestWithToken("Bearer " + token);

      assertTrue(doFilter(filter, request, new MockHttpServletResponse()));

      VerifiedUser user = OAuthFilter.verifiedUser(request).orElseThrow();
      assertEquals(Set.of("read", "write"), user.scopes());
    }

    @Test
    @DisplayName("the header carries a bare token with no Bearer prefix")
    void bareToken() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      MockHttpServletRequest request = requestWithToken(tokens.sign(TestTokens.validClaims().build()));

      assertTrue(doFilter(filter, request, new MockHttpServletResponse()));
    }

    @Test
    @DisplayName("no token is sent and the route does not require one")
    void unauthenticatedRouteWhenAuthIsOptional() throws Exception {
      OAuthFilter filter =
          new OAuthFilter(new OAuthConfig(Set.of(), null, null, null, false), tokens.verifier());
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
      MockHttpServletResponse response = new MockHttpServletResponse();

      assertTrue(doFilter(filter, request, response));

      assertEquals(HttpServletResponse.SC_OK, response.getStatus());
      assertNull(request.getAttribute(OAuthFilter.USER_ATTRIBUTE));
      assertTrue(OAuthFilter.verifiedUser(request).isEmpty(), "no token means no verified caller");
    }
  }

  @Nested
  @DisplayName("rejects with")
  class Rejects {

    private void assertRejected(OAuthFilter filter, MockHttpServletRequest request, int status, String code)
        throws Exception {
      MockHttpServletResponse response = new MockHttpServletResponse();

      assertFalse(doFilter(filter, request, response), "the request must not reach the handler");

      assertEquals(status, response.getStatus());
      assertEquals("{\"error\":\"" + code + "\"}", bodyOf(response));
      assertEquals("no-store", response.getHeader("Cache-Control"));
      assertTrue(response.getContentType().startsWith("application/json"));
      assertNull(request.getAttribute(OAuthFilter.USER_ATTRIBUTE));
    }

    @Test
    @DisplayName("401 oauth.missing_token when the header is absent")
    void missingToken() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken(null),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.missing_token");
    }

    @Test
    @DisplayName("401 oauth.missing_token when the header is whitespace only")
    void blankToken() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("   "),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.missing_token");
    }

    @Test
    @DisplayName("401 oauth.decode_error when the header carries a scheme and nothing else")
    void schemeWithoutAToken() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer   "),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.decode_error");
    }

    @Test
    @DisplayName("401 oauth.missing_token for an Authorization header, which is never a user token")
    void authorizationHeaderIsIgnored() throws Exception {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke");
      request.addHeader("Authorization", "Bearer " + tokens.sign(TestTokens.validClaims().build()));

      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          request,
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.missing_token");
    }

    @Test
    @DisplayName("401 oauth.invalid_signature for a token signed by someone else")
    void badSignature() throws Exception {
      String foreignToken = TestTokens.generate().sign(TestTokens.validClaims().build());

      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer " + foreignToken),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.invalid_signature");
    }

    @Test
    @DisplayName("401 oauth.expired for a token past its expiry")
    void expiredToken() throws Exception {
      String token = tokens.sign(TestTokens.validClaims()
          .expirationTime(new Date(System.currentTimeMillis() - 3_600_000L))
          .build());

      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer " + token),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.expired");
    }

    @Test
    @DisplayName("401 oauth.invalid_issuer for a token from another issuer")
    void wrongIssuer() throws Exception {
      String token = tokens.sign(TestTokens.validClaims().issuer("https://elsewhere.example").build());

      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer " + token),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.invalid_issuer");
    }

    @Test
    @DisplayName("401 oauth.decode_error for a token that is not a JWT at all")
    void garbageToken() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer not-a-jwt"),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.decode_error");
    }

    @Test
    @DisplayName("403 oauth.missing_scope for a verified caller without the required scope")
    void missingScope() throws Exception {
      String token = tokens.sign(TestTokens.validClaims().claim("scp", List.of("agent.invoke")).build());

      assertRejected(
          new OAuthFilter(new OAuthConfig(Set.of("admin.write")), tokens.verifier()),
          requestWithToken("Bearer " + token),
          HttpServletResponse.SC_FORBIDDEN,
          "oauth.missing_scope");
    }

    @Test
    @DisplayName("503 oauth.verifier_unavailable when key material cannot be fetched")
    void verifierUnavailable() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), TestTokens.unavailableVerifier()),
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "oauth.verifier_unavailable");
    }

    @Test
    @DisplayName("503 oauth.not_configured when no issuer can be discovered")
    void notConfigured() throws Exception {
      // Coordinates that cannot produce a usable JWKS endpoint. The filter must refuse rather than
      // serve the request unauthenticated.
      OAuthFilter filter = new OAuthFilter(
          new OAuthConfig(Set.of(), TestTokens.ISSUER, null, "not a url", true), null);

      assertRejected(
          filter,
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "oauth.not_configured");
    }

    @Test
    @DisplayName("503 oauth.verifier_unavailable when verification fails in a way nothing anticipated")
    void unexpectedVerificationFailure() throws Exception {
      // Not one of the three typed failures, so it must not leave the filter as a framework 500 with
      // no body and no Cache-Control.
      TokenVerifier exploding = token -> {
        throw new IllegalStateException("the key store went away");
      };

      assertRejected(
          new OAuthFilter(new OAuthConfig(), exploding),
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "oauth.verifier_unavailable");
    }

    @Test
    @DisplayName("503 oauth.verifier_unavailable when the verifier build fails unexpectedly")
    void unexpectedVerifierBuildFailure() throws Exception {
      // The same guarantee on the other side of the seam: no configuration reaches this, since
      // every refusal discovery makes is already IdentityNotConfiguredException.
      OAuthFilter filter = new OAuthFilter(new OAuthConfig()) {
        @Override
        TokenVerifier createVerifier() {
          throw new UnsupportedOperationException("the key store went away");
        }
      };

      assertRejected(
          filter,
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "oauth.verifier_unavailable");
    }

    @Test
    @DisplayName("a token the verifier rejects keeps its own code, ahead of the broad catch")
    void typedFailuresAreNotSwallowedByTheBroadCatch() throws Exception {
      String expired = tokens.sign(TestTokens.validClaims()
          .expirationTime(new Date(System.currentTimeMillis() - 3_600_000L))
          .build());
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          requestWithToken("Bearer " + expired),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.expired");

      String unprivileged =
          tokens.sign(TestTokens.validClaims().claim("scp", List.of("agent.invoke")).build());
      assertRejected(
          new OAuthFilter(new OAuthConfig(Set.of("admin.write")), tokens.verifier()),
          requestWithToken("Bearer " + unprivileged),
          HttpServletResponse.SC_FORBIDDEN,
          "oauth.missing_scope");

      assertRejected(
          new OAuthFilter(new OAuthConfig(), TestTokens.unavailableVerifier()),
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "oauth.verifier_unavailable");
    }

    @Test
    @DisplayName("a request with no token when the route requires one, even on a health-shaped path")
    void requireAuthAppliesToEveryPath() throws Exception {
      assertRejected(
          new OAuthFilter(new OAuthConfig(), tokens.verifier()),
          new MockHttpServletRequest("GET", "/health"),
          HttpServletResponse.SC_UNAUTHORIZED,
          "oauth.missing_token");
    }
  }

  @Nested
  @DisplayName("outbound propagation")
  class Outbound {

    @Test
    @DisplayName("parks the raw token for the handler, and clears it afterwards")
    void tokenIsScopedToTheRequest() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      String token = tokens.sign(TestTokens.validClaims().build());
      AtomicReference<String> seenByHandler = new AtomicReference<>();
      FilterChain chain = (request, response) -> seenByHandler.set(IdentityContext.currentUserToken());

      filter.doFilter(requestWithToken("Bearer " + token), new MockHttpServletResponse(), chain);

      assertEquals(token, seenByHandler.get());
      assertNull(IdentityContext.currentUserToken(), "the token must not outlive the request");
    }

    @Test
    @DisplayName("clears the token even when the handler throws")
    void tokenIsClearedOnFailure() {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      String token = tokens.sign(TestTokens.validClaims().build());
      FilterChain exploding = (request, response) -> {
        throw new IllegalStateException("handler blew up");
      };

      List<String> failures = new ArrayList<>();
      try {
        filter.doFilter(requestWithToken("Bearer " + token), new MockHttpServletResponse(), exploding);
      } catch (Exception e) {
        failures.add(e.getMessage());
      }

      assertEquals(List.of("handler blew up"), failures);
      assertNull(IdentityContext.currentUserToken());
    }

    @Test
    @DisplayName("a rejected request leaves no token behind")
    void noTokenAfterRejection() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());

      doFilter(filter, requestWithToken("Bearer not-a-jwt"), new MockHttpServletResponse());

      assertNull(IdentityContext.currentUserToken());
    }
  }

  @Nested
  @DisplayName("the typed accessor")
  class VerifiedUserAccessor {

    @Test
    @DisplayName("hands back the caller the filter verified, with no cast at the call site")
    void returnsTheVerifiedCaller() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      MockHttpServletRequest request =
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build()));

      assertTrue(doFilter(filter, request, new MockHttpServletResponse()));

      assertEquals("alice@example.com", OAuthFilter.verifiedUser(request).orElseThrow().subject());
    }

    @Test
    @DisplayName("is empty on a request the filter never verified")
    void emptyWithoutAVerifiedCaller() {
      assertTrue(OAuthFilter.verifiedUser(new MockHttpServletRequest("GET", "/health")).isEmpty());
    }

    @Test
    @DisplayName("is empty rather than throwing when the attribute holds something else")
    void emptyWhenTheAttributeWasOverwritten() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
      request.setAttribute(OAuthFilter.USER_ATTRIBUTE, "not a user");

      assertTrue(OAuthFilter.verifiedUser(request).isEmpty());
    }

    @Test
    @DisplayName("reads the same attribute the raw key names, which stays public")
    void readsThePublicAttributeKey() {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke");
      VerifiedUser user = VerifiedUser.fromClaims(Map.of("sub", "alice@example.com"));
      request.setAttribute(OAuthFilter.USER_ATTRIBUTE, user);

      assertEquals(user, OAuthFilter.verifiedUser(request).orElseThrow());
    }
  }

  @Test
  @DisplayName("the request attribute name matches the cross-SDK contract")
  void userAttributeName() {
    assertEquals("diagrid.user", OAuthFilter.USER_ATTRIBUTE);
  }

  @Test
  @DisplayName("a null config means 'any verified caller', not 'no checks'")
  void nullConfigStillRequiresAuth() throws Exception {
    OAuthFilter filter = new OAuthFilter(null, tokens.verifier());
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertFalse(doFilter(filter, requestWithToken(null), response));

    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
  }

  @Nested
  @DisplayName("async dispatch")
  class AsyncDispatch {

    private MockHttpServletRequest asyncRequest(String headerValue) {
      MockHttpServletRequest request = requestWithToken(headerValue);
      request.setDispatcherType(DispatcherType.ASYNC);
      return request;
    }

    @Test
    @DisplayName("re-establishes the caller's token, so a streaming handler can still propagate it")
    void tokenIsReEstablishedOnTheAsyncDispatch() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      String token = tokens.sign(TestTokens.validClaims().build());
      MockHttpServletRequest request = asyncRequest("Bearer " + token);
      AtomicReference<String> seenByHandler = new AtomicReference<>();

      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (req, res) -> seenByHandler.set(IdentityContext.currentUserToken()));

      assertEquals(token, seenByHandler.get(), "the async dispatch must carry the caller's token");
      assertEquals("alice@example.com", OAuthFilter.verifiedUser(request).orElseThrow().subject());
      assertNull(IdentityContext.currentUserToken(), "the token must not outlive the dispatch");
    }

    @Test
    @DisplayName("is rejected when it carries no token, like any other dispatch")
    void unauthenticatedAsyncDispatchIsRejected() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      MockHttpServletResponse response = new MockHttpServletResponse();

      assertFalse(doFilter(filter, asyncRequest(null), response));

      assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
      assertEquals("{\"error\":\"oauth.missing_token\"}", bodyOf(response));
    }

    @Test
    @DisplayName("writes no error body onto a response the handler has already committed")
    void committedResponseIsNotOverwritten() throws Exception {
      OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
      MockHttpServletResponse response = new MockHttpServletResponse();
      response.setCommitted(true);

      assertFalse(doFilter(filter, asyncRequest("Bearer not-a-jwt"), response));

      assertEquals("", bodyOf(response), "a committed stream must not be appended to");
    }
  }

  @Nested
  @DisplayName("verifier construction")
  class VerifierConstruction {

    private void assertNotConfigured(OAuthFilter filter) throws Exception {
      MockHttpServletResponse response = new MockHttpServletResponse();
      String token = tokens.sign(TestTokens.validClaims().build());

      assertFalse(doFilter(filter, requestWithToken("Bearer " + token), response));

      assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.getStatus());
      assertEquals("{\"error\":\"oauth.not_configured\"}", bodyOf(response));
    }

    @Test
    @DisplayName("a failed discovery is not repeated on the very next request")
    void discoveryIsNotRetriedInsideTheBackoffWindow() throws Exception {
      CountingFilter filter = new CountingFilter();

      assertNotConfigured(filter);
      assertNotConfigured(filter);
      assertNotConfigured(filter);

      assertEquals(1, filter.attempts.get(), "discovery must not run again inside the backoff window");
    }

    @Test
    @DisplayName("discovery is retried once the backoff window has passed")
    void discoveryIsRetriedAfterTheBackoffWindow() throws Exception {
      CountingFilter filter = new CountingFilter();
      assertNotConfigured(filter);

      filter.clock = filter.clock.plus(OAuthFilter.VERIFIER_RETRY_BACKOFF).plusSeconds(1);
      assertNotConfigured(filter);

      assertEquals(2, filter.attempts.get(), "the filter must not give up on a sidecar forever");
    }

    @Test
    @DisplayName("a verifier that eventually builds is used, and the failure is forgotten")
    void recoversOnceDiscoverySucceeds() throws Exception {
      CountingFilter filter = new CountingFilter();
      assertNotConfigured(filter);
      filter.clock = filter.clock.plus(OAuthFilter.VERIFIER_RETRY_BACKOFF).plusSeconds(1);
      filter.built = tokens.verifier();

      assertTrue(doFilter(
          filter,
          requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build())),
          new MockHttpServletResponse()));

      assertEquals(2, filter.attempts.get());
    }
  }

  /** Counts discovery attempts and fails them until {@link #built} is set. */
  private static final class CountingFilter extends OAuthFilter {

    private final AtomicInteger attempts = new AtomicInteger();
    private Instant clock = Instant.parse("2026-01-01T00:00:00Z");
    private TokenVerifier built;

    CountingFilter() {
      super(new OAuthConfig());
    }

    @Override
    TokenVerifier createVerifier() {
      attempts.incrementAndGet();
      if (built == null) {
        throw new IdentityNotConfiguredException("no issuer could be discovered");
      }
      return built;
    }

    @Override
    Instant now() {
      return clock;
    }
  }

  @Test
  @DisplayName("a nested dispatch inside the same request is not verified a second time")
  void runsOncePerRequest() throws Exception {
    OAuthFilter filter = new OAuthFilter(new OAuthConfig(), tokens.verifier());
    MockHttpServletRequest request = requestWithToken("Bearer " + tokens.sign(TestTokens.validClaims().build()));
    List<String> subjectsSeen = new ArrayList<>();

    // The handler re-enters the filter, as a forward or an include would.
    FilterChain reentrant = (req, res) -> {
      req.removeAttribute(OAuthFilter.USER_ATTRIBUTE);
      filter.doFilter(req, res, new MockFilterChain());
      VerifiedUser user = (VerifiedUser) req.getAttribute(OAuthFilter.USER_ATTRIBUTE);
      subjectsSeen.add(user == null ? "not re-verified" : user.subject());
    };

    filter.doFilter(request, new MockHttpServletResponse(), reentrant);

    assertEquals(List.of("not re-verified"), subjectsSeen);
  }
}
