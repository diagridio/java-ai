package io.diagrid.springai.durable.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.diagrid.springai.durable.workflow.AgentRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the deterministic workflow instance id from a request — the entire durability mechanism.
 * A reissued request that maps to the same id attaches to the in-flight or completed workflow
 * instead of starting a new one, so completed activities are never re-executed.
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>Conversation id present</b> → {@code dsa-c-<conversationId>-t<turnIndex>}, where
 *       {@code turnIndex} is the message count of the call. This needs no hashing and is robust to
 *       non-deterministic prompt content (RAG ordering, timestamps): a retry of the same turn
 *       resends the same messages → same count → same id; the next turn carries more messages → a
 *       new id. The conversation id is the natural, stable durability key (e.g. Spring AI's
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

  static final String CONVERSATION_PREFIX = "dsa-c-";
  static final String HASH_PREFIX = "dsa-h-";

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
      int turnIndex = request.messages() == null ? 0 : request.messages().size();
      return CONVERSATION_PREFIX + sanitize(conversationId) + "-t" + turnIndex;
    }
    if (requireConversationId) {
      throw new IllegalStateException(
          "No conversation id present and strict mode is enabled. Set a conversation id "
              + "(e.g. ChatMemory.CONVERSATION_ID) or disable strict mode.");
    }
    return HASH_PREFIX + toHex(sha256(canonicalBytes(request)));
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
