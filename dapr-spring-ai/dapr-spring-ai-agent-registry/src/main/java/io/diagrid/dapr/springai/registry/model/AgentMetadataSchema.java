package io.diagrid.dapr.springai.registry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The top-level agent record written to the Dapr state store, matching the Python {@code dapr-agents}
 * registration schema (v0.11.1) so the same registry/dashboard tooling can read it.
 *
 * <p>Serialized with {@code @JsonInclude(NON_NULL)} so unset optional sections (memory, pubsub, …)
 * are omitted rather than written as {@code null}.
 *
 * @param version      writer/schema version (informational provenance)
 * @param name         agent name (the registry key suffix)
 * @param registeredAt ISO-8601 registration timestamp
 * @param agent        core agent identity/configuration
 * @param llm          model/provider information, or {@code null}
 * @param tools        tools advertised by the agent, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMetadataSchema(
    @JsonProperty("version") String version,
    @JsonProperty("name") String name,
    @JsonProperty("registered_at") String registeredAt,
    @JsonProperty("agent") AgentMetadata agent,
    @JsonProperty("llm") LlmMetadata llm,
    @JsonProperty("tools") List<ToolMetadata> tools) {
}
