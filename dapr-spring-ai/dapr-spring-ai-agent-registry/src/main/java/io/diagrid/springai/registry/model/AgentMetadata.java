package io.diagrid.springai.registry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Core agent identity and configuration, nested under {@link AgentMetadataSchema#agent()}.
 *
 * @param appId        Dapr application id of the agent
 * @param type         agent type (e.g. {@code Agent})
 * @param role         agent role, or {@code null}
 * @param goal         high-level objective, or {@code null}
 * @param instructions ordered instructions, or {@code null}
 * @param systemPrompt the agent's system prompt, or {@code null}
 * @param framework    the framework the agent is built with (e.g. {@code spring-ai})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMetadata(
    @JsonProperty("appid") String appId,
    @JsonProperty("type") String type,
    @JsonProperty("role") String role,
    @JsonProperty("goal") String goal,
    @JsonProperty("instructions") List<String> instructions,
    @JsonProperty("system_prompt") String systemPrompt,
    @JsonProperty("framework") String framework) {
}
