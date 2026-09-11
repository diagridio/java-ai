package io.diagrid.springai.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.GetStateRequest;
import io.dapr.client.domain.State;
import io.dapr.utils.TypeRef;
import io.diagrid.springai.registry.model.AgentMetadataSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Two behaviours are pinned here. The team index is a read-modify-write under an etag, so a concurrent
 * registration can lose the race and be dropped — the registrar retries a rejected index write
 * (re-reading the etag) instead of swallowing it after one attempt. And the startup write preserves
 * the authored fields of a record already in the store, so restarting an app does not erase the
 * system prompt an earlier call recorded (java-ai#56).
 */
class AgentRegistrarTest {

  private static final String STORE = "agent-registry";
  private static final String INDEX_KEY = "agents:default:_index";
  private static final String RECORD_KEY = "agents:default:weatherAssistant";

  private final AgentRecordFactory factory =
      new AgentRecordFactory(
          "travel-app", "OllamaChatModel", "ollama", "default-model", () -> java.util.List.of());

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void indexUpdateRetriesOnEtagConflictThenSucceeds() {
    DaprClient client = mock(DaprClient.class);
    AgentMetadataSchema schema = factory.buildThin("weatherAssistant", false);

    // Index read always returns an empty index carrying an etag.
    State<Map> indexState = new State<>(INDEX_KEY, new HashMap(), "etag-1");
    doReturn(Mono.just(indexState))
        .when(client)
        .getState(any(GetStateRequest.class), any(TypeRef.class));

    // The per-agent record write succeeds.
    when(client.saveState(eq(STORE), eq(RECORD_KEY), any(), any(), any(), any()))
        .thenReturn(Mono.empty());
    // The index write is rejected once (stale etag), then succeeds on retry.
    when(client.saveState(eq(STORE), eq(INDEX_KEY), any(), any(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("etag mismatch")))
        .thenReturn(Mono.empty());

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    boolean ok = registrar.register(schema);

    assertTrue(ok, "a retried index conflict must still count as a successful registration");
    verify(client, times(2)).saveState(eq(STORE), eq(INDEX_KEY), any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void agentAlreadyInIndexIsNotWrittenAgain() {
    DaprClient client = mock(DaprClient.class);
    AgentMetadataSchema schema = factory.buildThin("weatherAssistant", false);

    Map existingIndex = new HashMap();
    existingIndex.put("agents", new java.util.ArrayList<>(java.util.List.of("weatherAssistant")));
    State<Map> indexState = new State<>(INDEX_KEY, existingIndex, "etag-1");
    doReturn(Mono.just(indexState))
        .when(client)
        .getState(any(GetStateRequest.class), any(TypeRef.class));
    when(client.saveState(eq(STORE), eq(RECORD_KEY), any(), any(), any(), any()))
        .thenReturn(Mono.empty());

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    assertTrue(registrar.register(schema));

    // Already listed → the index is not rewritten.
    verify(client, times(0)).saveState(eq(STORE), eq(INDEX_KEY), any(), any(), any(), any());
  }

  // --- registerPreservingAuthored: the startup write must not erase an earlier call's prompt ---

  /** Stubs the record read for {@link #RECORD_KEY} with the given stored value, and the index read empty. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubReads(DaprClient client, Map storedRecord) {
    doReturn(Mono.just(new State<>(INDEX_KEY, new HashMap(), "etag-1")))
        .when(client)
        .getState(argThat(r -> r != null && INDEX_KEY.equals(r.getKey())), any(TypeRef.class));
    doReturn(Mono.just(new State<>(RECORD_KEY, storedRecord, "etag-1")))
        .when(client)
        .getState(argThat(r -> r != null && RECORD_KEY.equals(r.getKey())), any(TypeRef.class));
    when(client.saveState(eq(STORE), any(String.class), any(), any(), any(), any()))
        .thenReturn(Mono.empty());
  }

  /** The record actually handed to saveState for the per-agent key. */
  private AgentMetadataSchema written(DaprClient client) {
    ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
    verify(client).saveState(eq(STORE), eq(RECORD_KEY), any(), value.capture(), any(), any());
    return (AgentMetadataSchema) value.getValue();
  }

  private static Map<String, Object> storedWith(String systemPrompt, List<String> instructions) {
    Map<String, Object> agent = new HashMap<>();
    agent.put("appid", "travel-app");
    agent.put("type", "Agent");
    if (systemPrompt != null) {
      agent.put("system_prompt", systemPrompt);
    }
    if (instructions != null) {
      agent.put("instructions", instructions);
    }
    Map<String, Object> record = new HashMap<>();
    record.put("name", "weatherAssistant");
    record.put("agent", agent);
    return record;
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void startupWriteCarriesTheStoredPromptOverTheThinRecord() {
    DaprClient client = mock(DaprClient.class);
    stubReads(client, (Map) storedWith("You are a weather assistant.", List.of("You are a weather assistant.")));

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    assertTrue(registrar.registerPreservingAuthored(factory.buildThin("weatherAssistant", false)));

    AgentMetadataSchema saved = written(client);
    assertEquals("You are a weather assistant.", saved.agent().systemPrompt(),
        "a restart must not erase the prompt the first call recorded");
    assertEquals(List.of("You are a weather assistant."), saved.agent().instructions());
    // The volatile half is still refreshed from the thin record.
    assertEquals("default-model", saved.llm().model());
    assertEquals("travel-app", saved.agent().appId());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void startupWriteStaysThinWhenNothingIsStored() {
    DaprClient client = mock(DaprClient.class);
    stubReads(client, null);

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    assertTrue(registrar.registerPreservingAuthored(factory.buildThin("weatherAssistant", false)));

    assertNull(written(client).agent().systemPrompt(), "a first-ever registration has no prompt to carry");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void startupWriteStaysThinWhenTheStoredRecordIsAlsoThin() {
    DaprClient client = mock(DaprClient.class);
    stubReads(client, (Map) storedWith(null, null));

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    assertTrue(registrar.registerPreservingAuthored(factory.buildThin("weatherAssistant", false)));

    AgentMetadataSchema saved = written(client);
    assertNull(saved.agent().systemPrompt());
    assertNull(saved.agent().instructions(), "an absent list must stay absent, not become empty");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void startupWriteStillHappensWhenTheRecordReadFails() {
    DaprClient client = mock(DaprClient.class);
    doReturn(Mono.just(new State<>(INDEX_KEY, new HashMap(), "etag-1")))
        .when(client)
        .getState(argThat(r -> r != null && INDEX_KEY.equals(r.getKey())), any(TypeRef.class));
    doReturn(Mono.error(new RuntimeException("state store unreachable")))
        .when(client)
        .getState(argThat(r -> r != null && RECORD_KEY.equals(r.getKey())), any(TypeRef.class));
    when(client.saveState(eq(STORE), any(String.class), any(), any(), any(), any()))
        .thenReturn(Mono.empty());

    AgentRegistrar registrar = new AgentRegistrar(client, STORE, "default");
    assertTrue(registrar.registerPreservingAuthored(factory.buildThin("weatherAssistant", false)),
        "a failed read must not cost the registration");
    assertNull(written(client).agent().systemPrompt());
  }
}
