package io.diagrid.springai.durable.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.Role;
import io.diagrid.springai.durable.workflow.AgentRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the deterministic workflow instance id from a request — the entire durability mechanism.
 * A reissued request that maps to the same id attaches to the in-flight or completed workflow
 * instead of starting a new one, so completed activities are never re-executed.
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>Conversation id present</b> → {@code dsa-c-<conversationId>} for the first turn, and
 *       {@code dsa-c-<conversationId>:<turn>} for later turns, where {@code turn} is the 0-based
 *       count of prior assistant replies in the request. This needs no hashing and is robust to
 *       non-deterministic prompt content (RAG ordering, timestamps): a retry of the same turn
 *       resends the same messages → same turn → same id; the next turn carries one more prior reply
 *       → a new id. The conversation id is the natural, stable durability key (e.g. Spring AI's
 *       {@code ChatMemory.CONVERSATION_ID}).</li>
 *   <li><b>No conversation id, strict mode</b> → fail fast, demanding a conversation id.</li>
 *   <li><b>No conversation id, lenient (default)</b> → {@code dsa-h-<sha256>} over a canonical
 *       serialization of the request. Preserves zero-config durability; a stateless retry that
 *       resends identical content reattaches.</li>
 * </ol>
 *
 * <p>Stateless and thread-safe.
 */
public final class InstanceIdDerivation {

  private static final Logger LOG = LoggerFactory.getLogger(InstanceIdDerivation.class);

  static final String CONVERSATION_PREFIX = "dsa-c-";
  static final String HASH_PREFIX = "dsa-h-";

  /** Warn at most once per JVM that durability is running on the content-hash fallback. */
  private static final AtomicBoolean HASH_FALLBACK_WARNED = new AtomicBoolean(false);

  private final boolean requireConversationId;
  private final ObjectWriter canonicalWriter;

  public InstanceIdDerivation() {
    this(false);
  }

  /**
   * @param requireConversationId strict mode: when {@code true}, a request without a conversation id
   *                              throws instead of falling back to a content hash
   */
  public InstanceIdDerivation(boolean requireConversationId) {
    this(requireConversationId, defaultCanonicalMapper());
  }

  public InstanceIdDerivation(boolean requireConversationId, ObjectMapper canonicalMapper) {
    this.requireConversationId = requireConversationId;
    this.canonicalWriter = canonicalMapper.writer();
  }

  private static ObjectMapper defaultCanonicalMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    return mapper;
  }

  /** Computes the content-addressable instance id for a request. */
  public String deriveInstanceId(AgentRequest request) {
    String conversationId = request.conversationId();
    if (conversationId != null && !conversationId.isBlank()) {
      String base = CONVERSATION_PREFIX + sanitize(conversationId);
      int turn = conversationTurn(request);
      // First turn (0) is just the conversation id; later turns get ":<turn>".
      return turn == 0 ? base : base + ":" + turn;
    }
    if (requireConversationId) {
      throw new IllegalStateException(
          "No conversation id present and strict mode is enabled. Set a conversation id "
              + "(e.g. ChatMemory.CONVERSATION_ID) or disable strict mode.");
    }
    if (HASH_FALLBACK_WARNED.compareAndSet(false, true)) {
      LOG.warn(
          "No conversationId on this ChatClient call; using a content-hash durability key. This is "
              + "fine for demos but NOT recommended for production (it is brittle to "
              + "non-deterministic prompt content). Set ChatMemory.CONVERSATION_ID, or set "
              + "dapr.spring-ai.require-conversation-id=true to enforce it. (Warning shown once.)");
    }
    return HASH_PREFIX + toHex(sha256(canonicalBytes(request)));
  }

  /**
   * 0-based conversation turn = completed prior rounds, derived as the number of assistant messages
   * already in the request. First call → 0 (no suffix); each subsequent turn carries one more prior
   * assistant reply → 1, 2, … A retry of the same turn resends the same messages → same count → same id.
   */
  private static int conversationTurn(AgentRequest request) {
    if (request.messages() == null) {
      return 0;
    }
    int turn = 0;
    for (MessageRecord message : request.messages()) {
      if (message.role() == Role.ASSISTANT) {
        turn++;
      }
    }
    return turn;
  }

  /** Keeps the id to id-like characters; other characters collapse to '_'. */
  private static String sanitize(String conversationId) {
    return conversationId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private byte[] canonicalBytes(AgentRequest request) {
    try {
      return canonicalWriter.writeValueAsBytes(request);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to canonicalize AgentRequest for instance id", e);
    }
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
