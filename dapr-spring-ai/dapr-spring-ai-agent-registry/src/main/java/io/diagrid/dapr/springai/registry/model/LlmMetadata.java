package io.diagrid.dapr.springai.registry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model/provider information for an agent, nested under {@link AgentMetadataSchema#llm()}.
 *
 * @param client   the ChatModel client class (e.g. {@code OllamaChatModel})
 * @param provider the inferred provider (e.g. {@code ollama})
 * @param api      API type (e.g. {@code chat})
 * @param model    the resolved model identifier
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmMetadata(
    @JsonProperty("client") String client,
    @JsonProperty("provider") String provider,
    @JsonProperty("api") String api,
    @JsonProperty("model") String model) {
}
