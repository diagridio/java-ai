package io.diagrid.dapr.springai.examples.travelplanner.agents.manual;

import io.diagrid.dapr.springai.examples.travelplanner.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * <b>Manual memory.</b> Where {@code TravelConcierge} relies on the auto-configured {@link ChatMemory}
 * bean, this concierge builds its own {@link MessageWindowChatMemory} explicitly on the injected
 * {@link ChatMemoryRepository} — which, under the {@code memory} profile, is the Dapr state-store
 * repository — with a custom window size, then attaches a {@link MessageChatMemoryAdvisor}. This is
 * the escape hatch when you want full control over the window and the backing store.
 *
 * <p>Gated to the {@code memory} profile so the Dapr-backed {@link ChatMemoryRepository} is active and
 * conversations survive restarts.
 */
@Component
@Profile("memory")
public class DurableMemoryConcierge {

    private static final String SYSTEM = """
            You are a travel concierge with a durable memory of the conversation. Help the user plan
            their trip, use the getWeather tool when useful, and remember details they shared earlier
            (destination, dates, interests) instead of asking again.""";

    private static final int WINDOW_MESSAGES = 40;

    private final ChatClient chat;

    public DurableMemoryConcierge(ChatClient.Builder builder, ChatMemoryRepository repository) {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)   // the Dapr state-store repo under the memory profile
                .maxMessages(WINDOW_MESSAGES)
                .build();
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultTools(new WeatherTools())
                .build();
    }

    /** One conversation turn; history is keyed by {@code conversationId} in the Dapr state store. */
    public String chat(String conversationId, String message) {
        return chat.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
