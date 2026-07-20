package io.diagrid.dapr.springai.examples.travelplanner.agents;

import io.diagrid.dapr.springai.examples.travelplanner.tools.FlightTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Searches and recommends flights between two cities. */
@Component
public class FlightFinder {

    private static final String SYSTEM = """
            You are a flight search assistant. Find the best available flights for the
            requested route and date. Use the searchFlights tool to get flight options,
            then recommend the best option considering price and convenience.
            Return a concise summary of the recommended flight.""";

    private final ChatClient chat;

    public FlightFinder(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new FlightTools())
                .build();
    }

    public String findFlights(String origin, String destination, String date) {
        return chat.prompt()
                .user("Find the best flights from " + origin + " to " + destination + " on " + date + ".")
                .call()
                .content();
    }
}
