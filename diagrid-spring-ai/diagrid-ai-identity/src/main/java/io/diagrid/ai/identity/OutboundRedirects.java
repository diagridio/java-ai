package io.diagrid.ai.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The redirect decision {@link IdentityAwareHttpClient} makes for itself.
 *
 * <p>The JDK follows redirects inside the client, below any point an application can intervene at,
 * which is exactly where the identity header would need reconsidering. So the delegate is built not
 * to follow and the hops are taken here instead, one request at a time, each one passing back
 * through the identity and origin checks.
 *
 * <p>The method and body rules are the ones every HTTP stack settles on: {@code 303} becomes a
 * {@code GET}, {@code 301} and {@code 302} become one unless the request was already a {@code GET}
 * or {@code HEAD}, and {@code 307} and {@code 308} keep both.
 *
 * <p>Taking the walk here also means the JDK never strips what a hop leaving the origin must not
 * carry, so that is done here too. The identity header is not one of those: it is decided per hop
 * against the origin the application addressed rather than against the previous hop.
 */
final class OutboundRedirects {

  private static final String LOCATION_HEADER = "Location";
  private static final String GET = "GET";
  private static final String HEAD = "HEAD";
  private static final String HTTPS = "https";

  private static final int MOVED_PERMANENTLY = 301;
  private static final int FOUND = 302;
  private static final int SEE_OTHER = 303;
  private static final int TEMPORARY_REDIRECT = 307;
  private static final int PERMANENT_REDIRECT = 308;

  /**
   * Credentials a hop that leaves the origin must not carry.
   *
   * <p>Scoped to the host the application handed them to: a callee that answers with a
   * {@code Location} elsewhere would otherwise choose who receives the application's own bearer
   * token, session cookie or proxy credential.
   */
  static final Set<String> CROSS_ORIGIN_STRIPPED_HEADERS =
      Set.of("authorization", "cookie", "proxy-authorization");

  /** Headers that describe a body, dropped along with the body when a hop becomes a {@code GET}. */
  private static final Set<String> BODY_HEADERS = Set.of("content-type", "content-encoding");

  private OutboundRedirects() {
  }

  /**
   * The request the next hop would be, if there is one.
   *
   * @param request the request that produced {@code response}
   * @param response the response to read the redirect out of
   * @param policy whether, and to where, redirects may be followed
   * @return the next request, or empty when this response ends the exchange
   */
  static Optional<HttpRequest> nextHop(HttpRequest request, HttpResponse<?> response, HttpClient.Redirect policy) {
    if (!isRedirect(response.statusCode())) {
      return Optional.empty();
    }
    String location = response.headers().firstValue(LOCATION_HEADER).orElse("");
    if (location.isBlank()) {
      return Optional.empty();
    }
    URI target;
    try {
      target = request.uri().resolve(location);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!permits(policy, request.uri(), target)) {
      return Optional.empty();
    }
    return Optional.of(hopTo(request, target, response.statusCode()));
  }

  private static boolean isRedirect(int status) {
    return status == MOVED_PERMANENTLY
        || status == FOUND
        || status == SEE_OTHER
        || status == TEMPORARY_REDIRECT
        || status == PERMANENT_REDIRECT;
  }

  /** {@code NORMAL} follows everything but a downgrade out of HTTPS, as the JDK defines it. */
  private static boolean permits(HttpClient.Redirect policy, URI from, URI to) {
    return switch (policy) {
      case ALWAYS -> true;
      case NORMAL -> !(HTTPS.equalsIgnoreCase(from.getScheme()) && !HTTPS.equalsIgnoreCase(to.getScheme()));
      case NEVER -> false;
    };
  }

  private static HttpRequest hopTo(HttpRequest request, URI target, int status) {
    boolean keepsMethod = keepsMethod(status, request.method());
    boolean sameOrigin = OutboundOrigin.sameOrigin(request.uri(), target);
    HttpRequest.Builder builder = HttpRequest.newBuilder(request, carried(sameOrigin, keepsMethod)).uri(target);
    return keepsMethod ? builder.build() : builder.GET().build();
  }

  /**
   * Which of the previous hop's headers the next one keeps.
   *
   * <p>Compared against the previous hop rather than the original origin: copying from a hop that
   * was already stripped makes the drop stick for the rest of the chain.
   */
  private static BiPredicate<String, String> carried(boolean sameOrigin, boolean keepsMethod) {
    return (name, value) -> {
      String lowercase = name.toLowerCase(Locale.ROOT);
      if (!sameOrigin && CROSS_ORIGIN_STRIPPED_HEADERS.contains(lowercase)) {
        return false;
      }
      return keepsMethod || !BODY_HEADERS.contains(lowercase);
    };
  }

  private static boolean keepsMethod(int status, String method) {
    if (status == TEMPORARY_REDIRECT || status == PERMANENT_REDIRECT) {
      return true;
    }
    return status != SEE_OTHER && (GET.equals(method) || HEAD.equals(method));
  }
}
