package io.diagrid.springai.examples.travelplanner.agents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Combines flight, hotel, and activity information into a final itinerary. LLM-only — no tools. */
@Component
public class ItineraryFormatter {

    private static final String SYSTEM = """
            You are a travel itinerary formatter. You will be given FLIGHTS, HOTELS, and
            ACTIVITIES information. Combine them into a clear, concise travel itinerary that includes:
            - Travel details (flight)
            - Accommodation details (hotel)
            - Day-by-day activity suggestions
            - Estimated total budget
            Format it in a readable way.""";

    private final ChatClient chat;

    public ItineraryFormatter(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .build();
    }

    public String format(String flights, String hotels, String activities) {
        return chat.prompt()
                .user("FLIGHTS: " + flights + "\n\nHOTELS: " + hotels + "\n\nACTIVITIES: " + activities)
                .call()
                .content();
    }
}
