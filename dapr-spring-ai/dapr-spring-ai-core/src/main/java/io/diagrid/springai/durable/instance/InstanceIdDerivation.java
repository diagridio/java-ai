package io.diagrid.springai.durable.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 *   <li><b>Conversation id present</b> → {@code dsa-c-<conversationId>-<hash8>}, where {@code hash8}
 *       is the first 8 hex chars of a SHA-256 over the canonical request. The conversation id groups
 *       a conversation's turns for readability; the content hash is the actual discriminator and
 *       makes an identical reissue of a turn reattach (same content → same hash → same id). Keying on
 *       <em>content</em> rather than a positional turn <em>count</em> is deliberate: Spring AI's
 *       {@code MessageWindowChatMemory} evicts old messages, so any count of prior replies is
 *       non-monotonic — two different turns can plateau on the same count and collide, attaching a
 *       later call to an earlier COMPLETED workflow and returning a stale answer. The content hash
 *       has no such plateau: distinct turns carry distinct message histories. Caveat: non-deterministic
 *       prompt content (reordered RAG chunks, injected timestamps) changes the hash, so a reissue that
 *       regenerates different content will not reattach — keep the seed prompt deterministic for
 *       reliable dedup.</li>
 *   <li><b>No conversation id, strict mode</b> → fail fast, demanding a conversation id.</li>
 *   <li><b>No conversation id, lenient (default)</b> → {@code dsa-h-<sha256>} over the same canonical
 *       serialization. Preserves zero-config durability; a stateless retry that resends identical
 *       content reattaches.</li>
 * </ol>
 *
 * <p><b>Drift from Dapr Agents.</b> Dapr Agents (Python) schedules every run under a random
 * {@code uuid.uuid4().hex} instance id unless the caller supplies one, and keeps conversation
 * continuity in an external memory store — it does not dedup or reattach by id, so a reissued run
 * simply starts a fresh workflow. This library instead derives the instance id deterministically from
 * the request, so a reissue reattaches to the in-flight/completed workflow rather than duplicating it
 * (no re-executed LLM calls or tool side effects). The trade-off is the caveat above: deterministic
 * reattach needs deterministic request content, whereas random ids are immune to content but give no
 * dedup.
 *
 * <p>Stateless and thread-safe.
 */
public final class InstanceIdDerivation {

  private static final Logger LOG = LoggerFactory.getLogger(InstanceIdDerivation.class);

  static final String CONVERSATION_PREFIX = "dsa-c-";
  static final String HASH_PREFIX = "dsa-h-";

  /** Hex length of the content hash appended to a conversation-keyed id (32 bits — namespaced per id). */
  static final int SHORT_HASH_LENGTH = 8;

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
      // dsa-c-<conversationId>-<hash8>: the conversation id groups the turns for readability; the
      // content hash distinguishes each turn and makes an identical reissue reattach. Keying on
      // content (not a reply count) is robust to ChatMemory window eviction — see the class javadoc.
      String contentHash = toHex(sha256(canonicalBytes(request))).substring(0, SHORT_HASH_LENGTH);
      return CONVERSATION_PREFIX + sanitize(conversationId) + "-" + contentHash;
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
