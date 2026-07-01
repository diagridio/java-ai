package io.diagrid.springai.registry.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tool advertised by an agent, as it appears in {@link AgentMetadataSchema#tools()}.
 *
 * @param name        tool name
 * @param description tool description
 * @param args        tool argument JSON schema
 */
public record ToolMetadata(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("args") String args) {
}
