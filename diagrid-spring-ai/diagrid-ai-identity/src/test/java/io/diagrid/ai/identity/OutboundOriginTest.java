package io.diagrid.ai.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The origin rule itself, including the one case a redirect test cannot reach without TLS: the
 * same-host upgrade from plaintext HTTP to HTTPS on their default ports.
 */
class OutboundOriginTest {

  @Test
  @DisplayName("the same origin, whether or not the default port is written out")
  void sameOrigin() {
    assertTrue(same("https://mcp.example:443/a", "https://mcp.example/b"));
    assertTrue(same("http://mcp.example/a", "http://mcp.example:80/b"));
    assertTrue(same("https://MCP.example/a", "https://mcp.example/b"));
  }

  @Test
  @DisplayName("an upgrade from http:80 to https:443 on the same host keeps the identity")
  void defaultPortUpgrade() {
    assertTrue(same("http://mcp.example/a", "https://mcp.example/a"));
    assertFalse(same("http://mcp.example:8080/a", "https://mcp.example/a"));
  }

  @Test
  @DisplayName("another host, another port, or a downgrade out of https is another origin")
  void otherOrigins() {
    assertFalse(same("https://mcp.example/a", "https://elsewhere.example/a"));
    assertFalse(same("https://mcp.example/a", "https://mcp.example:8443/a"));
    assertFalse(same("https://mcp.example/a", "http://mcp.example/a"));
  }

  private static boolean same(String original, String target) {
    return OutboundOrigin.sameOrigin(URI.create(original), URI.create(target));
  }
}
