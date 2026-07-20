package io.diagrid.dapr.springai.durable.workflow;

/**
 * Serializable projection of a Spring AI {@code ToolDefinition} — the tool surface presented to the
 * model. Part of the workflow input, and a component of the derived instance id (a change to the
 * available tools is a different logical request).
 *
 * @param name        unique tool name (also the activity dispatch key)
 * @param description model-facing description
 * @param inputSchema JSON schema of the tool's input parameters
 */
public record ToolSpec(String name, String description, String inputSchema) {
}
