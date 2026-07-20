package io.diagrid.dapr.springai.examples.travelplanner.agents;

import io.diagrid.dapr.springai.examples.travelplanner.tools.FlakyApiTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Confirms a travel booking using an unreliable provider tool. The tool throws on every even
 * invocation; because each tool call is a durable activity, the dapr-spring-ai retry policy retries
 * it transparently, so the agent still returns a confirmation. Demonstrates activity-level retries.
 */
@Component
public class BookingAgent {

    private static final String SYSTEM = """
            You are a travel booking assistant. Confirm the customer's booking using the
            confirmBooking tool and report the confirmation code it returns. Always use the
            tool; never make up a confirmation code.""";

    private final ChatClient chat;

    public BookingAgent(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new FlakyApiTools())
                .build();
    }

    public String confirmBooking(String reference) {
        return chat.prompt()
                .user("Confirm the booking with reference " + reference + ".")
                .call()
                .content();
    }
}
