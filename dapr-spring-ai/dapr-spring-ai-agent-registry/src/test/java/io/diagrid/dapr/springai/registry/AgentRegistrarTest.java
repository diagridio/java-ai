package io.diagrid.dapr.springai.registry;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import io.diagrid.dapr.springai.registry.model.AgentMetadataSchema;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * The team index is a read-modify-write under an etag, so a concurrent registration can lose the race
 * and be dropped. The registrar retries a rejected index write (re-reading the etag) instead of
 * swallowing it after one attempt.
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
}
