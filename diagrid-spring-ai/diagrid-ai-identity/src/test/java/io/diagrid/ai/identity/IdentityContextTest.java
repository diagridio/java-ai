package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentityContextTest {

  @AfterEach
  void clearToken() {
    IdentityContext.clearCurrentToken();
  }

  @Test
  void roundTripsTheToken() {
    IdentityContext.setCurrentToken("abc123");

    assertEquals("abc123", IdentityContext.currentUserToken());
  }

  @Test
  void clearingForgetsTheToken() {
    IdentityContext.setCurrentToken("abc123");
    IdentityContext.clearCurrentToken();

    assertNull(IdentityContext.currentUserToken());
  }

  @Test
  void thereIsNoTokenByDefault() {
    assertNull(IdentityContext.currentUserToken());
  }

  @Test
  void producesTheBearerHeader() {
    IdentityContext.setCurrentToken("tok");

    assertEquals(Map.of("X-Diagrid-User-Token", "Bearer tok"), IdentityContext.outboundIdentityHeaders());
  }

  @Test
  @DisplayName("omits the header entirely outside an authenticated request")
  void noHeaderWithoutAToken() {
    assertTrue(IdentityContext.outboundIdentityHeaders().isEmpty());
  }

  @Test
  @DisplayName("a token parked on one thread is not visible from another")
  void tokenIsPerThread() throws Exception {
    IdentityContext.setCurrentToken("abc123");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<String> otherThread = executor.submit(IdentityContext::currentUserToken);

      assertNull(otherThread.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void stripsTheBearerPrefixCaseInsensitively() {
    assertEquals("tok", IdentityContext.trimBearer("Bearer tok"));
    assertEquals("tok", IdentityContext.trimBearer("bearer tok"));
    assertEquals("tok", IdentityContext.trimBearer("BEARER tok"));
    assertEquals("tok", IdentityContext.trimBearer("  Bearer   tok  "));
  }

  @Test
  void acceptsABareTokenWithNoPrefix() {
    assertEquals("tok", IdentityContext.trimBearer("tok"));
  }

  @Test
  @DisplayName("a blank or absent header reads as no token at all")
  void blankHeader() {
    assertEquals("", IdentityContext.trimBearer(null));
    assertEquals("", IdentityContext.trimBearer(""));
    assertEquals("", IdentityContext.trimBearer("   "));
  }

  @Test
  @DisplayName("a scheme with nothing after it is treated as the token, as in the reference SDK")
  void schemeWithoutAToken() {
    // The prefix is stripped only when its trailing space survives trimming, so "Bearer " yields
    // "Bearer" and is rejected downstream as an undecodable token rather than as a missing one.
    assertEquals("Bearer", IdentityContext.trimBearer("Bearer "));
    assertEquals("Bearer", IdentityContext.trimBearer("Bearer   "));
  }

  @Test
  @DisplayName("the header name and scheme match the reference SDK")
  void constantsMatchTheReferenceSdk() {
    assertEquals("X-Diagrid-User-Token", IdentityContext.USER_TOKEN_HEADER);
    assertEquals("Bearer ", IdentityContext.BEARER_PREFIX);
  }
}
