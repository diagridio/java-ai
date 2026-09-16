package io.diagrid.springai.examples.identity;

import io.diagrid.ai.identity.IdentityHttpClient;
import io.diagrid.ai.identity.VerifiedUser;
import io.diagrid.springai.identity.OAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two things a handler does with a verified caller: read it, and pass it on.
 *
 * <p>Both routes sit behind the filter installed in {@code IdentityApplication}, so neither one
 * checks a token itself — an unverified request never reaches them.
 */
@RestController
public class IdentityController {

  /** The scope {@code /whoami} reports on, to show {@code hasScope} on the verified caller. */
  private static final String READ_SCOPE = "read";

  /** The example's own error string for a failed outbound call — not an SDK error code. */
  private static final String DOWNSTREAM_UNREACHABLE = "downstream_unreachable";

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /**
   * The SDK's identity-aware client, built once and shared: it reads the caller at send time, so
   * one long-lived client serves every request with that request's own caller.
   */
  private final HttpClient http =
      IdentityHttpClient.from(HttpClient.newBuilder().connectTimeout(TIMEOUT), HttpClient.Redirect.NORMAL);

  private final URI downstreamUri;

  public IdentityController(@Value("${downstream.url}") String downstreamUrl) {
    this.downstreamUri = URI.create(downstreamUrl);
  }

  /** The verified caller, read through the typed accessor rather than out of the attribute bag. */
  @GetMapping("/whoami")
  public Whoami whoami(HttpServletRequest request) {
    // Present on every request that gets here: the policy is fail-closed.
    VerifiedUser user = OAuthFilter.verifiedUser(request).orElseThrow();
    return new Whoami(
        user.subject(), user.tenant(), List.copyOf(user.scopes()), user.hasScope(READ_SCOPE));
  }

  /** One outbound call. The client carries the caller's identity, so the callee verifies the same user. */
  @GetMapping("/downstream")
  public ResponseEntity<Map<String, String>> downstream() {
    HttpRequest outbound = HttpRequest.newBuilder(downstreamUri).timeout(TIMEOUT).GET().build();
    try {
      HttpResponse<String> response = http.send(outbound, HttpResponse.BodyHandlers.ofString());
      return ResponseEntity.ok(Map.of("downstream", response.body()));
    } catch (IOException e) {
      return unreachable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable();
    }
  }

  private static ResponseEntity<Map<String, String>> unreachable() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", DOWNSTREAM_UNREACHABLE));
  }

  /** The {@code /whoami} body. Record components are the JSON keys, in this order. */
  public record Whoami(String subject, String tenant, List<String> scopes, boolean hasRead) {
  }
}
