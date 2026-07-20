package io.diagrid.dapr.springai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.State;
import io.dapr.client.domain.StateOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Mono;

/**
 * saveAll uses optimistic concurrency: it re-reads the current etag and writes conditionally on it,
 * translating only an etag conflict into a {@link ConcurrentConversationModificationException}.
 */
class DaprStateChatMemoryRepositoryTest {

  private static final String STORE = "agent-memory";
  private static final String CONVERSATION = "trip-42";
  private static final String KEY = "default:_memory_trip-42";

  private final DaprClient client = mock(DaprClient.class);
  private final DaprStateChatMemoryRepository repository =
      new DaprStateChatMemoryRepository(client, STORE, "default");

  private void existingStateWithEtag(String etag) {
    doReturn(Mono.just(new State<>(KEY, new StoredConversation(List.of()), etag)))
        .when(client)
        .getState(eq(STORE), eq(KEY), eq(StoredConversation.class));
  }

  @Test
  void happyPathWritesWithTheFreshlyReadEtag() {
    existingStateWithEtag("etag-7");
    when(client.saveState(eq(STORE), eq(KEY), eq("etag-7"), any(), any(StateOptions.class)))
        .thenReturn(Mono.empty());

    repository.saveAll(CONVERSATION, List.of(new UserMessage("hi")));

    verify(client).saveState(eq(STORE), eq(KEY), eq("etag-7"), any(), any(StateOptions.class));
  }

  @Test
  void etagConflictThrowsNamingTheConversationId() {
    existingStateWithEtag("etag-7");
    when(client.saveState(eq(STORE), eq(KEY), eq("etag-7"), any(), any(StateOptions.class)))
        .thenReturn(Mono.error(new RuntimeException("rpc error: possible etag mismatch")));

    ConcurrentConversationModificationException e =
        assertThrows(
            ConcurrentConversationModificationException.class,
            () -> repository.saveAll(CONVERSATION, List.of(new UserMessage("hi"))));
    assertTrue(e.getMessage().contains(CONVERSATION), e.getMessage());
  }

  @Test
  void firstWriteUsesFirstWriteConcurrencyWhenNoExistingState() {
    // No record yet → nothing to read → null etag; FIRST_WRITE is still passed to guard the create.
    doReturn(Mono.empty())
        .when(client)
        .getState(eq(STORE), eq(KEY), eq(StoredConversation.class));
    when(client.saveState(eq(STORE), eq(KEY), isNull(), any(), any(StateOptions.class)))
        .thenReturn(Mono.empty());

    repository.saveAll(CONVERSATION, List.of(new UserMessage("hi")));

    ArgumentCaptor<StateOptions> options = ArgumentCaptor.forClass(StateOptions.class);
    verify(client).saveState(eq(STORE), eq(KEY), isNull(), any(), options.capture());
    assertEquals(StateOptions.Concurrency.FIRST_WRITE, options.getValue().getConcurrency());
  }

  @Test
  void nonConflictFailurePropagatesUnchanged() {
    existingStateWithEtag("etag-7");
    when(client.saveState(eq(STORE), eq(KEY), eq("etag-7"), any(), any(StateOptions.class)))
        .thenReturn(Mono.error(new IllegalStateException("connection refused")));

    // Not an etag conflict → propagates as-is, not translated to ConcurrentConversationModification.
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> repository.saveAll(CONVERSATION, List.of(new UserMessage("hi"))));
    assertEquals("connection refused", e.getMessage());
  }
}
