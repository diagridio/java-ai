package io.diagrid.springai.durable.workflow;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallback} that carries only a tool definition and refuses execution.
 *
 * <p>The LLM activity attaches these to the prompt so the model knows the available tools and can
 * emit tool-call requests. In Spring AI 2.0 the ChatModel returns tool calls without executing them
 * (verified empirically), so {@link #call} must never run; tool execution happens in a separate
 * Dapr activity. If {@code call} is ever reached it signals a wiring mistake and fails loudly rather
 * than silently bypassing durability.
 */
public final class DefinitionOnlyToolCallback implements ToolCallback {

  private final ToolDefinition definition;

  public DefinitionOnlyToolCallback(ToolSpec spec) {
    this.definition =
        ToolDefinition.builder()
            .name(spec.name())
            .description(spec.description())
            .inputSchema(spec.inputSchema())
            .build();
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  @Override
  public String call(String toolInput) {
    throw new UnsupportedOperationException(
        "Durable tools execute as Dapr activities, not at the ChatModel layer: "
            + definition.name());
  }
}
