package io.diagrid.dapr.springai.durable.workflow;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallback} that carries only a tool definition and refuses execution.
 *
 * <p>The LLM activity attaches these to the prompt so the model knows the available tools and can
 * emit tool-call requests. On the durable path the ChatModel must return those requests to the
 * workflow rather than executing them in-process — the workflow runs each tool as its own Dapr
 * activity. Spring AI 2.0 GA removed the {@code internalToolExecutionEnabled} options flag that once
 * disabled in-model execution by contract; whether a model executes tools is now decided by its
 * {@code ToolCallingManager} / {@code ToolExecutionEligibilityChecker}. This callback is therefore
 * the loud backstop: if the model ever does try to execute a tool, {@link #call} throws instead of
 * silently bypassing durability, turning a wiring mistake into an obvious failure.
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
