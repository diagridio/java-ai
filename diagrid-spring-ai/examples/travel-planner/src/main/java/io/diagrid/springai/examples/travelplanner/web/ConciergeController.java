package io.diagrid.springai.examples.travelplanner.web;

import io.diagrid.springai.examples.travelplanner.agents.TravelConcierge;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-turn chat endpoint demonstrating per-conversation memory.
 *
 * <p>{@code GET /chat?message=<text>[&conversationId=<id>]} — the {@code conversationId} groups a
 * conversation's turns (the concierge recalls earlier ones); different ids are isolated. It is
 * <b>optional</b>: when omitted the server assigns a fresh one. Either way the effective id is
 * returned in the {@code X-Conversation-Id} response header, so a client can capture it and thread
 * it through follow-up turns without hard-coding one.
 *
 * <p>Note: {@code conversationId} is chat-memory grouping only — it is <em>not</em> the durability
 * key. Every call runs under its own random workflow instance id (dapr-agents parity).
 */
@RestController
public class ConciergeController {

    /** Response header carrying the effective conversationId (supplied or server-assigned). */
    static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";

    private final TravelConcierge concierge;

    public ConciergeController(TravelConcierge concierge) {
        this.concierge = concierge;
    }

    @GetMapping("/chat")
    public ResponseEntity<String> chat(
            @RequestParam(required = false) String conversationId, @RequestParam String message) {
        String id =
                (conversationId == null || conversationId.isBlank())
                        ? UUID.randomUUID().toString()
                        : conversationId;
        String answer = concierge.chat(id, message);
        return ResponseEntity.ok().header(CONVERSATION_ID_HEADER, id).body(answer);
    }
}
