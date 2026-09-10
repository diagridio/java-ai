package io.diagrid.springai.registry;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.GetStateRequest;
import io.dapr.client.domain.State;
import io.dapr.utils.TypeRef;
import io.diagrid.springai.registry.model.AgentMetadata;
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
 * <p>{@link #register} overwrites any prior record for the same name, so a first-call enrichment
 * supersedes the thin record written at startup. {@link #registerPreservingAuthored} is the same
 * write with the authored fields carried over from the stored record, which is what keeps a restart
 * from erasing a system prompt an earlier call recorded. Never throws into the caller — a registry
 * write must not break the user's ChatClient call.
 */
public final class AgentRegistrar {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRegistrar.class);
  private static final String AGENTS_KEY = "agents";
  private static final String INDEX_SUFFIX = ":_index";
  private static final String AGENT_SECTION = "agent";
  private static final String ROLE_KEY = "role";
  private static final String GOAL_KEY = "goal";
  private static final String INSTRUCTIONS_KEY = "instructions";
  private static final String SYSTEM_PROMPT_KEY = "system_prompt";
  private static final int INDEX_MAX_ATTEMPTS = 3;

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

  /**
   * Writes the agent record like {@link #register}, but first carries the authored fields (role,
   * goal, instructions, system prompt) over from a record already in the store.
   *
   * <p>This is what the startup registration uses. The startup record is thin — built without a live
   * call, so it knows no system prompt — and an unconditional write would erase the prompt a previous
   * run's first call had recorded. Everything else (timestamp, model, tools, workflow name) is still
   * taken from the incoming record, so a restart keeps the record current.
   *
   * @param schema the record to write
   * @return {@code true} if the write succeeded; failures are logged and swallowed
   */
  public boolean registerPreservingAuthored(AgentMetadataSchema schema) {
    return register(carryOverAuthored(schema));
  }

  // Fills in any authored field the incoming record leaves unset from the stored record's "agent"
  // section. Never throws: if the read fails the incoming record is written as-is, because losing the
  // prompt is better than losing the registration.
  private AgentMetadataSchema carryOverAuthored(AgentMetadataSchema schema) {
    AgentMetadata incoming = schema.agent();
    if (incoming == null) {
      return schema;
    }
    Map<String, Object> stored = readAgentSection(schema.name());
    if (stored.isEmpty()) {
      return schema;
    }

    String role = incoming.role() != null ? incoming.role() : text(stored.get(ROLE_KEY));
    String goal = incoming.goal() != null ? incoming.goal() : text(stored.get(GOAL_KEY));
    String systemPrompt = incoming.systemPrompt() != null
        ? incoming.systemPrompt()
        : text(stored.get(SYSTEM_PROMPT_KEY));
    List<String> instructions = incoming.instructions();
    if (instructions == null && stored.get(INSTRUCTIONS_KEY) instanceof List<?> list) {
      List<String> texts = list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
      instructions = texts.isEmpty() ? null : texts;
    }
    if (role == null && goal == null && systemPrompt == null && instructions == null) {
      return schema;
    }

    LOG.info("Carried the authored fields of agent '{}' over from its stored record", schema.name());
    AgentMetadata merged = new AgentMetadata(incoming.appId(), incoming.type(), role, goal,
        instructions, systemPrompt, incoming.framework(), incoming.metadata());
    return new AgentMetadataSchema(
        schema.version(), schema.name(), schema.registeredAt(), merged, schema.llm(), schema.tools());
  }

  // The stored record's "agent" section as a raw map — empty when there is no record, it carries no
  // agent section, or the read failed. Read as a Map rather than the record type on purpose: records
  // written by another adapter carry fields this model does not declare.
  @SuppressWarnings({"unchecked", "rawtypes"})
  private Map<String, Object> readAgentSection(String name) {
    String prefix = keyPrefix();
    try {
      State<Map> state = client
          .getState(
              new GetStateRequest(statestore, prefix + ":" + name).setMetadata(meta(prefix)),
              TypeRef.get(Map.class))
          .block();
      if (state == null || state.getValue() == null) {
        return Map.of();
      }
      Object section = ((Map<String, Object>) state.getValue()).get(AGENT_SECTION);
      return section instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    } catch (RuntimeException e) {
      LOG.debug("Could not read the stored record for agent '{}' ({}); writing the new record as-is",
          name, e.toString());
      return Map.of();
    }
  }

  // A stored JSON value as text, or null when it is absent, not a string, or blank.
  private static String text(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  private void write(AgentMetadataSchema schema) {
    String prefix = keyPrefix();
    Map<String, String> meta = meta(prefix);

    client.saveState(statestore, prefix + ":" + schema.name(), null, schema, meta, null).block();
    ensureIndexed(prefix + INDEX_SUFFIX, meta, schema.name());
  }

  private String keyPrefix() {
    return AGENTS_KEY + ":" + team;
  }

  // contentType=application/json keeps the value stored as JSON; partitionKey groups the team. The
  // read and the write must agree on this metadata or the read will not find the record.
  private static Map<String, String> meta(String prefix) {
    return Map.of("contentType", "application/json", "partitionKey", prefix);
  }

  // The team index is a read-modify-write under an etag, so two agents registering at once can race:
  // the loser's etag is stale and its write is rejected, silently dropping it from the index. Retry a
  // few times, re-reading the etag each attempt, so a rejected write re-applies onto the latest index
  // instead of being lost. A final failure propagates to register(), which logs and swallows it.
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void ensureIndexed(String indexKey, Map<String, String> meta, String name) {
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= INDEX_MAX_ATTEMPTS; attempt++) {
      State<Map> indexState =
          client.getState(new GetStateRequest(statestore, indexKey).setMetadata(meta), TypeRef.get(Map.class))
              .block();
      Map<String, Object> index = indexState != null && indexState.getValue() != null
          ? new HashMap<>((Map<String, Object>) indexState.getValue())
          : new HashMap<>();
      List<String> names = index.get(AGENTS_KEY) instanceof List<?> existing
          ? new ArrayList<>((List<String>) existing)
          : new ArrayList<>();
      if (names.contains(name)) {
        return;
      }
      names.add(name);
      index.put(AGENTS_KEY, names);
      String etag = indexState != null ? indexState.getEtag() : null;
      try {
        client.saveState(statestore, indexKey, etag, index, meta, null).block();
        return;
      } catch (RuntimeException e) {
        lastError = e;
        LOG.debug("Team index update for '{}' rejected on attempt {}/{} (etag conflict?); retrying",
            name, attempt, INDEX_MAX_ATTEMPTS);
      }
    }
    throw lastError != null
        ? lastError
        : new IllegalStateException("Team index update failed for agent '" + name + "'");
  }
}
