package io.diagrid.springai.durable.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.workflow.AgentRequest;
import io.diagrid.springai.durable.workflow.ChatOptionsSpec;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The instance id is the durability handle, so it must be byte-stable for the same logical call and
 * sensitive to anything that distinguishes one. These tests cover the conversation-id path (the
 * primary key), strict mode, and the content-hash fallback for stateless calls.
 */
class InstanceIdDerivationTest {

  private final InstanceIdDerivation derivation = new InstanceIdDerivation();

  private List<MessageRecord> seedMessages() {
    return List.of(
        MessageRecord.system("You are a travel agent."),
        MessageRecord.user("book me a flight to Madrid"));
  }

  private ChatOptionsSpec options(String model, Double temperature) {
    return new ChatOptionsSpec(model, temperature, null, null, null, null, null, null);
  }

  private AgentRequest statelessRequest() {
    return new AgentRequest(
        seedMessages(),
        List.of(new ToolSpec("bookFlight", "Book a flight", "{\"type\":\"object\"}")),
        options("openai", 0.7));
  }

  private AgentRequest conversationRequest(String conversationId, List<MessageRecord> messages) {
    return new AgentRequest(
        messages,
        List.of(new ToolSpec("bookFlight", "Book a flight", "{\"type\":\"object\"}")),
        options("openai", 0.7),
        conversationId);
  }

  // ---- Conversation-id path (primary): dsa-c-<id>-<hash8> ----

  @Test
  void conversationIdPathIsPrefixPlusShortContentHash() {
    String id = derivation.deriveInstanceId(conversationRequest("conv-42", seedMessages()));
    assertTrue(id.matches("dsa-c-conv-42-[0-9a-f]{8}"), "expected dsa-c-<id>-<hash8>, got: " + id);
  }

  @Test
  void identicalReissueYieldsSameId() {
    // A genuine retry resends the same request → same content hash → same id → reattaches.
    assertEquals(
        derivation.deriveInstanceId(conversationRequest("conv-42", seedMessages())),
        derivation.deriveInstanceId(conversationRequest("conv-42", seedMessages())));
  }

  @Test
  void windowEvictionDoesNotCollideAcrossTurns() {
    // Regression for the turn-counter collision. Two DIFFERENT turns of one conversation that, under
    // MessageWindowChatMemory eviction, present the SAME number of assistant messages (here 1). The
    // old count-based key derived the same id for both, so the later call attached to the earlier
    // COMPLETED workflow and returned a stale answer without calling the LLM. Keying on content keeps
    // the turns distinct.
    List<MessageRecord> earlyTurn =
        List.of(
            MessageRecord.system("You are a travel agent."),
            MessageRecord.user("book me a flight to Madrid"),
            MessageRecord.assistant("Booked your flight.", List.of()),
            MessageRecord.user("and a hotel"));
    List<MessageRecord> laterTurn =
        List.of(
            MessageRecord.system("You are a travel agent."),
            MessageRecord.user("and a hotel"), // window slid: the earlier flight Q/A was evicted
            MessageRecord.assistant("Booked your hotel.", List.of()),
            MessageRecord.user("what's the weather in Madrid?"));
    assertNotEquals(
        derivation.deriveInstanceId(conversationRequest("trip", earlyTurn)),
        derivation.deriveInstanceId(conversationRequest("trip", laterTurn)),
        "turns with an equal assistant-count but different content must not collide");
  }

  @Test
  void differentConversationsYieldDifferentIds() {
    assertNotEquals(
        derivation.deriveInstanceId(conversationRequest("conv-A", seedMessages())),
        derivation.deriveInstanceId(conversationRequest("conv-B", seedMessages())));
  }

  @Test
  void conversationIdIsSanitizedForUnsafeCharacters() {
    String id = derivation.deriveInstanceId(conversationRequest("user/42 session", seedMessages()));
    assertTrue(
        id.matches("dsa-c-user_42_session-[0-9a-f]{8}"),
        "unsafe chars must collapse to '_' with the hash suffix following: " + id);
  }

  // ---- Strict mode ----

  @Test
  void strictModeThrowsWhenNoConversationId() {
    InstanceIdDerivation strict = new InstanceIdDerivation(true);
    assertThrows(IllegalStateException.class, () -> strict.deriveInstanceId(statelessRequest()));
  }

  @Test
  void strictModeStillWorksWithConversationId() {
    InstanceIdDerivation strict = new InstanceIdDerivation(true);
    assertTrue(
        strict.deriveInstanceId(conversationRequest("conv-42", seedMessages()))
            .matches("dsa-c-conv-42-[0-9a-f]{8}"));
  }

  // ---- Content-hash fallback (stateless, lenient) ----

  @Test
  void fallbackIdHasHashPrefixAndSha256HexLength() {
    String id = derivation.deriveInstanceId(statelessRequest());
    assertTrue(id.startsWith("dsa-h-"), "expected dsa-h- prefix, got: " + id);
    String hex = id.substring("dsa-h-".length());
    assertEquals(64, hex.length(), "SHA-256 hex must be 64 chars");
    assertTrue(hex.matches("[0-9a-f]{64}"), "must be lowercase hex: " + hex);
  }

  @Test
  void fallbackIsDeterministicAndStatelessAcrossInstances() {
    String a = new InstanceIdDerivation().deriveInstanceId(statelessRequest());
    String b = new InstanceIdDerivation().deriveInstanceId(statelessRequest());
    assertEquals(a, b);
  }

  @Test
  void fallbackDistinguishesMessagesToolsAndOptions() {
    AgentRequest base = statelessRequest();
    AgentRequest otherMessages =
        new AgentRequest(
            List.of(
                MessageRecord.system("You are a travel agent."),
                MessageRecord.user("book me a flight to Barcelona")),
            base.toolSpecs(),
            base.options());
    AgentRequest otherTools =
        new AgentRequest(
            base.messages(),
            List.of(new ToolSpec("cancelFlight", "Cancel a flight", "{\"type\":\"object\"}")),
            base.options());
    AgentRequest otherOptions =
        new AgentRequest(base.messages(), base.toolSpecs(), options("openai", 0.2));

    String baseId = derivation.deriveInstanceId(base);
    assertNotEquals(baseId, derivation.deriveInstanceId(otherMessages));
    assertNotEquals(baseId, derivation.deriveInstanceId(otherTools));
    assertNotEquals(baseId, derivation.deriveInstanceId(otherOptions));
  }

  @Test
  void fallbackGoldenValueGuardsByteStability() {
    // Pins the exact digest so an accidental change to the canonical serialization is caught.
    // Regenerate intentionally if AgentRequest/MessageRecord field shape changes.
    String expected = "dsa-h-507b29338fc3d279ec97a50494f9f2c25f1a5014300d349c40510ce73694016c";
    assertEquals(expected, derivation.deriveInstanceId(statelessRequest()));
  }
}
