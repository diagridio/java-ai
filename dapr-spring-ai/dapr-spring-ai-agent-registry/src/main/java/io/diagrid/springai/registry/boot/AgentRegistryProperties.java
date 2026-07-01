package io.diagrid.springai.registry.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Dapr agent registry.
 *
 * @param enabled    whether ChatClient agents are auto-registered (default true)
 * @param statestore Dapr state store component name (default {@code agent-registry} — the registry
 *                   store Catalyst provides out of the box, configured with {@code keyPrefix: none})
 * @param team       registry team that namespaces the agent keys (default {@code default})
 * @param appId      Dapr application id recorded on each agent; when blank, resolved from
 *                   {@code spring.application.name}
 */
@ConfigurationProperties("dapr.spring-ai.registry")
public record AgentRegistryProperties(Boolean enabled, String statestore, String team, String appId) {

  public AgentRegistryProperties {
    if (enabled == null) {
      enabled = true;
    }
    if (statestore == null || statestore.isBlank()) {
      statestore = "agent-registry";
    }
    if (team == null || team.isBlank()) {
      team = "default";
    }
  }
}
