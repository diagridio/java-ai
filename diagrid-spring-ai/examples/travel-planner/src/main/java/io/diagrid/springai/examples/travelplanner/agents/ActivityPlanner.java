package io.diagrid.springai.examples.travelplanner.agents;

import io.diagrid.springai.examples.travelplanner.tools.ActivityTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Plans activities and attractions based on the traveler's interests. */
@Component
public class ActivityPlanner {

    private static final String SYSTEM = """
            You are a travel activities planner. Find the best activities and attractions
            in the requested destination matching the traveler's interests. Use the
            searchActivities tool to get options, then select the top 3 that best match
            the interests. Return a concise list of recommended activities with brief
            descriptions.""";

    private final ChatClient chat;

    public ActivityPlanner(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new ActivityTools())
                .build();
    }

    public String planActivities(String destination, String interests) {
        return chat.prompt()
                .user("Find activities in " + destination + " matching these interests: " + interests + ".")
                .call()
                .content();
    }
}
