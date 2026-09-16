package io.diagrid.ai.identity;

import java.net.URI;
import java.util.Locale;

/**
 * Whether an outbound request still addresses the origin the caller asked for.
 *
 * <p>This is what stops a redirect from handing the caller's on-behalf-of token to a host the
 * callee chose. Nothing below this client makes that decision, since the delegate is built not to
 * follow redirects; {@link OutboundRedirects#CROSS_ORIGIN_STRIPPED_HEADERS} is the matching rule
 * for what the application itself sent.
 *
 * <p>The rule is the same scheme, host and effective port, with one exception: a same-host upgrade
 * from plaintext HTTP on port 80 to HTTPS on port 443.
 */
final class OutboundOrigin {

  private static final String HTTP = "http";
  private static final String HTTPS = "https";
  private static final int HTTP_DEFAULT_PORT = 80;
  private static final int HTTPS_DEFAULT_PORT = 443;

  private OutboundOrigin() {
  }

  /**
   * Whether {@code target} is the same origin as {@code original}.
   *
   * @param original the URI the application called
   * @param target the URI the request is about to go to, which is a later redirect hop when the two
   *     differ
   * @return {@code true} when the caller's identity may travel to {@code target}
   */
  static boolean sameOrigin(URI original, URI target) {
    if (!host(original).equalsIgnoreCase(host(target))) {
      return false;
    }
    boolean unchanged = scheme(original).equals(scheme(target)) && port(original) == port(target);
    return unchanged || upgradedToHttps(original, target);
  }

  /** A same-host upgrade to HTTPS keeps the identity: the hop is strictly more protected, not less. */
  private static boolean upgradedToHttps(URI original, URI target) {
    return HTTP.equals(scheme(original))
        && port(original) == HTTP_DEFAULT_PORT
        && HTTPS.equals(scheme(target))
        && port(target) == HTTPS_DEFAULT_PORT;
  }

  private static String host(URI uri) {
    return uri.getHost() == null ? "" : uri.getHost();
  }

  private static String scheme(URI uri) {
    return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
  }

  /** The port the request actually goes to, which the URI leaves implicit on a default port. */
  private static int port(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    if (HTTPS.equals(scheme(uri))) {
      return HTTPS_DEFAULT_PORT;
    }
    return HTTP.equals(scheme(uri)) ? HTTP_DEFAULT_PORT : -1;
  }
}
