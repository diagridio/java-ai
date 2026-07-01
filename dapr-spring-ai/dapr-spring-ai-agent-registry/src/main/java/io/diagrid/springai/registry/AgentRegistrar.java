package io.diagrid.springai.registry;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.GetStateRequest;
import io.dapr.client.domain.State;
import io.dapr.utils.TypeRef;
import io.diagrid.springai.registry.model.AgentMetadataSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes agent records to a Dapr state store using the Python {@code dapr-agents} protocol: a
 * per-agent key {@code agents:{team}:{name}} plus a team index {@code agents:{team}:_index} that
 * lists the team's agents.
 *
 * <p>Writing overwrites any prior record for the same name (so a first-call enrichment supersedes
 * the thin record written at startup). Never throws into the caller — a registry write must not
 * break the user's ChatClient call.
 */
public final class AgentRegistrar {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRegistrar.class);
  private static final String AGENTS_KEY = "agents";
  private static final String INDEX_SUFFIX = ":_index";

  private final DaprClient client;
  private final String statestore;
  private final String team;

  /**
   * @param client     Dapr client for state operations
   * @param statestore state store component name
   * @param team       registry team (namespaces the keys)
   */
  public AgentRegistrar(DaprClient client, String statestore, String team) {
    this.client = client;
    this.statestore = statestore;
    this.team = team;
  }

  /**
   * Writes the agent record (overwriting any prior one) and ensures the team index lists it.
   *
   * @param schema the record to write
   * @return {@code true} if the write succeeded; failures are logged and swallowed
   */
  public boolean register(AgentMetadataSchema schema) {
    try {
      write(schema);
      LOG.info("Registered agent '{}' in state store '{}'", schema.name(), statestore);
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Failed to register agent '{}' in state store '{}': {}",
          schema.name(), statestore, e.toString());
      return false;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void write(AgentMetadataSchema schema) {
    String prefix = AGENTS_KEY + ":" + team;
    // contentType=application/json keeps the value stored as JSON; partitionKey groups the team.
    Map<String, String> meta = Map.of("contentType", "application/json", "partitionKey", prefix);

    client.saveState(statestore, prefix + ":" + schema.name(), null, schema, meta, null).block();

    // Team index: read, append the name if absent, write back with the current etag.
    String indexKey = prefix + INDEX_SUFFIX;
    State<Map> indexState =
        client.getState(new GetStateRequest(statestore, indexKey).setMetadata(meta), TypeRef.get(Map.class))
            .block();

    Map<String, Object> index = indexState != null && indexState.getValue() != null
        ? new HashMap<>((Map<String, Object>) indexState.getValue())
        : new HashMap<>();
    List<String> names = index.get(AGENTS_KEY) instanceof List<?> existing
        ? new ArrayList<>((List<String>) existing)
        : new ArrayList<>();
    if (names.contains(schema.name())) {
      return;
    }
    names.add(schema.name());
    index.put(AGENTS_KEY, names);
    String etag = indexState != null ? indexState.getEtag() : null;
    client.saveState(statestore, indexKey, etag, index, meta, null).block();
  }
}
