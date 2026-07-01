package io.diagrid.springai.examples.travelplanner.web;

import io.diagrid.springai.examples.travelplanner.agents.TravelConcierge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-turn chat endpoint demonstrating per-conversation memory.
 *
 * <p>{@code GET /chat?conversationId=<id>&message=<text>} — requests with the same
 * {@code conversationId} share history (the concierge recalls earlier turns); different ids are
 * isolated.
 */
@RestController
public class ConciergeController {

    private final TravelConcierge concierge;

    public ConciergeController(TravelConcierge concierge) {
        this.concierge = concierge;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String conversationId, @RequestParam String message) {
        return concierge.chat(conversationId, message);
    }
}
