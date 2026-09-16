package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Origin pinning: the caller's token goes only to the origin the caller addressed.
 *
 * <p>Two servers, both on loopback, addressed by two different host spellings — that is what makes
 * the second one a different origin without leaving the loopback interface. A redirect naming a host
 * the callee chose is exactly the case this guard exists for.
 */
class IdentityHttpClientRedirectTest {

  private static final String CALLED_HOST = "127.0.0.1";
  private static final String OTHER_HOST = "localhost";
  private static final String TOKEN = "Bearer caller-token";
  private static final int OK = 200;
  private static final int FOUND = 302;

  @BeforeAll
  static void bothHostsAreLoopback() throws Exception {
    assumeTrue(
        InetAddress.getByName(OTHER_HOST).isLoopbackAddress(),
        "the test stays on loopback: " + OTHER_HOST + " must resolve there");
  }

  @AfterEach
  void clearToken() {
    IdentityContext.clearCurrentToken();
  }

  @Test
  @DisplayName("a redirect to another host does NOT carry the identity header")
  void dropsIdentityOnARedirectAwayFromTheOriginCalled() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST);
        RecordingHttpServer elsewhere = RecordingHttpServer.start(OTHER_HOST)) {
      elsewhere.ok("/elsewhere", "elsewhere");
      called.redirect("/cross", elsewhere.uri("/elsewhere").toString());
      IdentityContext.setCurrentToken("caller-token");

      HttpResponse<String> response = send(following(), called.uri("/cross"));

      assertEquals(OK, response.statusCode());
      assertEquals("elsewhere", response.body());
      assertEquals(List.of(TOKEN), called.identityHeader("/cross"), "the origin called carried it");
      assertTrue(elsewhere.wasCalled("/elsewhere"), "the redirect was followed");
      assertEquals(List.of(), elsewhere.identityHeader("/elsewhere"), "another host must not see it");
    }
  }

  @Test
  @DisplayName("a redirect within the same origin keeps the identity header")
  void keepsIdentityOnASameOriginRedirect() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST)) {
      called.redirect("/hop", "/landed").ok("/landed", "landed");
      IdentityContext.setCurrentToken("caller-token");

      HttpResponse<String> response = send(following(), called.uri("/hop"));

      assertEquals(OK, response.statusCode());
      assertEquals("landed", response.body());
      assertEquals(List.of(TOKEN), called.identityHeader("/hop"));
      assertEquals(List.of(TOKEN), called.identityHeader("/landed"));
    }
  }

  @Test
  @DisplayName("a client asked not to follow redirects hands the 302 back untouched")
  void doesNotFollowWhenTheCallerSaidNotTo() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST)) {
      called.redirect("/hop", "/landed").ok("/landed", "landed");
      IdentityContext.setCurrentToken("caller-token");
      HttpClient client = IdentityHttpClient.newHttpClient();

      HttpResponse<String> response = send(client, called.uri("/hop"));

      assertEquals(FOUND, response.statusCode());
      assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
      assertEquals(List.of(), called.identityHeader("/landed"), "the hop was never made");
    }
  }

  @Test
  @DisplayName("the async path pins the origin as well")
  void dropsIdentityOnARedirectAwayFromTheOriginCalledAsync() throws Exception {
    try (RecordingHttpServer called = RecordingHttpServer.start(CALLED_HOST);
        RecordingHttpServer elsewhere = RecordingHttpServer.start(OTHER_HOST)) {
      elsewhere.ok("/elsewhere", "elsewhere");
      called.redirect("/cross", elsewhere.uri("/elsewhere").toString());
      IdentityContext.setCurrentToken("caller-token");

      HttpResponse<String> response =
          following()
              .sendAsync(HttpRequest.newBuilder(called.uri("/cross")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .get();

      assertEquals(OK, response.statusCode());
      assertEquals(List.of(TOKEN), called.identityHeader("/cross"));
      assertEquals(List.of(), elsewhere.identityHeader("/elsewhere"));
    }
  }

  private static HttpClient following() {
    return IdentityHttpClient.from(HttpClient.newBuilder(), HttpClient.Redirect.NORMAL);
  }

  private static HttpResponse<String> send(HttpClient client, URI uri) throws Exception {
    return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
