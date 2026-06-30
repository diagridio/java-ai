package io.diagrid.springai.durable.workflow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Maps a tool name to its implementation, invoked by {@link ToolInvokeActivity}.
 *
 * <p>Tools must be registered at worker startup with a stable name and must not capture per-request
 * state — the activity may execute on any replica or after recovery. The implementation takes the
 * model-produced JSON arguments string and returns a string result.
 */
public final class ToolRegistry {

  private final ConcurrentHashMap<String, Function<String, String>> tools = new ConcurrentHashMap<>();

  public ToolRegistry register(String name, Function<String, String> implementation) {
    tools.put(name, implementation);
    return this;
  }

  public boolean has(String name) {
    return tools.containsKey(name);
  }

  /** Invokes the named tool with the given JSON arguments, returning its string result. */
  public String invoke(String name, String arguments) {
    Function<String, String> implementation = tools.get(name);
    if (implementation == null) {
      throw new IllegalArgumentException("No tool registered with name: " + name);
    }
    return implementation.apply(arguments);
  }
}
