package io.diagrid.springai.examples.travelplanner.agents;

import io.diagrid.springai.examples.travelplanner.tools.ActivityTools;
import io.diagrid.springai.examples.travelplanner.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

/**
 * A conversational travel concierge that remembers the conversation across turns.
 *
 * <p>Memory is managed by a {@link MessageChatMemoryAdvisor} backed by the auto-configured
 * {@link ChatMemory} (in-memory by default). Each request carries a {@code conversationId} via
 * {@link ChatMemory#CONVERSATION_ID}: requests sharing an id share history; different ids are
 * isolated. The concierge also has weather + activity tools, so it can answer using details the
 * user mentioned earlier in the same conversation (e.g. their destination).
 */
@Component
public class TravelConcierge {

    private static final String SYSTEM = """
            You are a friendly travel concierge with memory of the whole conversation.
            Help the user plan their trip. Use your tools to answer questions about the weather
            and activities for a city. Remember details the user shared earlier in this
            conversation (their destination, interests, dates) and refer back to them instead of
            asking again.""";

    private final ChatClient chat;

    public TravelConcierge(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(new WeatherTools(), new ActivityTools())
                .build();
    }

    /** One conversation turn. History is keyed by {@code conversationId}. */
    public String chat(String conversationId, String message) {
        return chat.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
