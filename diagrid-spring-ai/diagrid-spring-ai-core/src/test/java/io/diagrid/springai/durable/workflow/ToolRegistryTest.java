package io.diagrid.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A tool-name collision (two different definitions under one name) is detected — without changing the
 * process-wide, last-write-wins execution behavior that compliant (stateless, uniquely-named) tools
 * rely on.
 */
class ToolRegistryTest {

  private final ToolRegistry registry = new ToolRegistry();

  private static ToolSpec spec(String name, String description) {
    return new ToolSpec(name, description, "{\"type\":\"object\"}");
  }

  @Test
  void differentDefinitionUnderSameNameIsDetectedButLastWriteStillWins() {
    registry.register(spec("account", "v1"), args -> "A");

    assertTrue(
        registry.isNewlyDetectedConflict(spec("account", "v2")),
        "a different definition under the same name must be detected");

    registry.register(spec("account", "v2"), args -> "B");
    assertEquals("B", registry.invoke("account", "{}"), "last-write-wins is unchanged");
  }

  @Test
  void identicalReRegistrationIsNeverAConflict() {
    ToolSpec spec = spec("getWeather", "same");
    registry.register(spec, args -> "sunny");

    // The normal per-call / per-replica path re-registers the identical tool; must stay silent.
    assertFalse(registry.isNewlyDetectedConflict(spec));
    assertFalse(registry.isNewlyDetectedConflict(spec("getWeather", "same")), "equal spec, new object");
  }

  @Test
  void conflictIsFlaggedAtMostOncePerName() {
    registry.register(spec("account", "v1"), args -> "A");

    assertTrue(registry.isNewlyDetectedConflict(spec("account", "v2")), "first mismatch flagged");
    assertFalse(registry.isNewlyDetectedConflict(spec("account", "v3")), "further mismatches deduped");
  }

  @Test
  void unknownToolThrowsNamingIt() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> registry.invoke("missing", "{}"));
    assertTrue(e.getMessage().contains("missing"), e.getMessage());
  }
}
