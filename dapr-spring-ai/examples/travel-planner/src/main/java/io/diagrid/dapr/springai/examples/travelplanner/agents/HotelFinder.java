package io.diagrid.dapr.springai.examples.travelplanner.agents;

import io.diagrid.dapr.springai.examples.travelplanner.tools.HotelTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Searches and recommends hotels in a city. */
@Component
public class HotelFinder {

    private static final String SYSTEM = """
            You are a hotel search assistant. Find hotels in the requested city for the
            given check-in date and number of nights. Use the searchHotels tool to get
            hotel options, then recommend the best option considering price, location,
            and rating. Return a concise summary of the recommended hotel.""";

    private final ChatClient chat;

    public HotelFinder(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new HotelTools())
                .build();
    }

    public String findHotels(String destination, String date, int nights) {
        return chat.prompt()
                .user("Find hotels in " + destination + " with check-in on " + date
                        + " for " + nights + " nights.")
                .call()
                .content();
    }
}
