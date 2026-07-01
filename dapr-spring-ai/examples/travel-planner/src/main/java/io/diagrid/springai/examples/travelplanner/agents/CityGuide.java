package io.diagrid.springai.examples.travelplanner.agents;

import io.diagrid.springai.examples.travelplanner.tools.CityGuideTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Produces a city travel guide using three tools and multi-step reasoning. */
@Component
public class CityGuide {

    private static final String SYSTEM = """
            You are an expert city travel guide. When asked about a city, you MUST:
            1. First find the top attractions using the findAttractions tool
            2. Then search for restaurants matching the user's cuisine preference using searchRestaurants
            3. Finally get transport information using getTransportInfo
            Combine all results into a well-organized city guide with sections for
            Attractions, Restaurants, and Getting Around.
            Keep it concise but informative.""";

    private final ChatClient chat;

    public CityGuide(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new CityGuideTools())
                .build();
    }

    public String createGuide(String city, String cuisine, int days) {
        return chat.prompt()
                .user("Create a city guide for " + city + ". Cuisine preference: " + cuisine
                        + ". Trip duration: " + days + " days.")
                .call()
                .content();
    }

    /** Improve an existing guide using the current weather — one refinement pass of the loop. */
    public String refine(String previousGuide, String weather, String city, String cuisine, int days) {
        return chat.prompt()
                .user("Here is the current city guide for " + city + " (cuisine: " + cuisine
                        + ", " + days + " days):\n\n" + previousGuide
                        + "\n\nCurrent weather: " + weather
                        + "\n\nImprove this guide: tighten the writing, fill any gaps, and weave in "
                        + "weather-appropriate suggestions. Keep the Attractions, Restaurants, and "
                        + "Getting Around sections.")
                .call()
                .content();
    }
}
