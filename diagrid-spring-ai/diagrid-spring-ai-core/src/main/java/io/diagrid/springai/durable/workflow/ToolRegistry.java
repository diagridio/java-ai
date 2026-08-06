package io.diagrid.springai.durable.workflow;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a tool name to its implementation (and definition), invoked by {@link ToolInvokeActivity}.
 *
 * <p>Registration is process-wide and <b>last-write-wins</b> by bare tool name — filled at startup
 * from {@code @Tool} beans and, per call, from request-scoped tools (which re-register the same tool
 * on every call, and on every replica that serves the agent). That identical re-registration is
 * exactly what lets a request-scoped tool execute wherever Dapr dispatches the activity, so it is by
 * design; tools must therefore be <b>stateless</b> and have <b>app-unique names</b>.
 *
 * <p>When two <i>different</i> tool definitions are registered under one name — a violation of the
 * unique-name contract — the shadowing would otherwise be silent and a workflow could execute the
 * wrong implementation. To make that detectable, registration compares the incoming {@link ToolSpec}
 * with the stored one and logs a WARN on a mismatch, <b>once per name</b> (so the normal identical
 * re-registration on every call/replica stays silent). Behavior is unchanged — still last-write-wins,
 * nothing new throws.
 *
 * <p>This cannot catch the other contract violation — identical tool <i>classes</i> that capture
 * per-request state in the closure — because two such registrations are indistinguishable from the
 * safe stateless case; that remains unsupported by contract.
 */
public final class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private final ConcurrentHashMap<String, ToolEntry> tools = new ConcurrentHashMap<>();
  // Names already warned about, so a persistent mismatch doesn't log on every (re-)registration.
  private final Set<String> conflictsWarned = ConcurrentHashMap.newKeySet();

  /**
   * Registers (or re-registers, last-write-wins) a tool by name. If a <i>different</i> {@link ToolSpec}
   * is already registered under this name, logs a one-time WARN — see the class javadoc.
   */
  public ToolRegistry register(ToolSpec spec, Function<String, String> implementation) {
    if (isNewlyDetectedConflict(spec)) {
      LOG.warn(
          "Two different tool definitions share the name '{}'. Tool execution is process-wide and "
              + "last-write-wins, so a workflow may run the wrong implementation. Give tools unique "
              + "names across the application (e.g. a per-agent prefix).",
          spec.name());
    }
    tools.put(spec.name(), new ToolEntry(spec, implementation));
    return this;
  }

  public boolean has(String name) {
    return tools.containsKey(name);
  }

  /** Invokes the named tool with the given JSON arguments, returning its string result. */
  public String invoke(String name, String arguments) {
    ToolEntry entry = tools.get(name);
    if (entry == null) {
      throw new IllegalArgumentException("No tool registered with name: " + name);
    }
    return entry.implementation().apply(arguments);
  }

  /**
   * Whether registering {@code spec} collides with a <i>different</i> definition already under its
   * name, recording the name so the WARN fires at most once per name. An identical re-registration
   * (equal {@link ToolSpec}) is never a conflict — that is the normal per-call/per-replica path.
   * Package-private so the collision decision can be unit-tested directly rather than via log output.
   *
   * @return {@code true} only the first time a mismatch is seen for this name
   */
  boolean isNewlyDetectedConflict(ToolSpec spec) {
    ToolEntry existing = tools.get(spec.name());
    if (existing == null || existing.spec().equals(spec)) {
      return false;
    }
    return conflictsWarned.add(spec.name());
  }

  // A tool's implementation plus the definition it was registered with (for collision detection).
  private record ToolEntry(ToolSpec spec, Function<String, String> implementation) {
  }
}
