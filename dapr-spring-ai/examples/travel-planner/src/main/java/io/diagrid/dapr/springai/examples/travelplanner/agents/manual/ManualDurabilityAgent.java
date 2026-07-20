package io.diagrid.dapr.springai.examples.travelplanner.agents.manual;

import io.diagrid.dapr.springai.durable.boot.DurableAdvisor;
import io.diagrid.dapr.springai.examples.travelplanner.tools.FlightTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * <b>Manual durability.</b> This agent builds its {@link ChatClient} from the static
 * {@link ChatClient#builder(ChatModel)} factory, which bypasses Spring AI's {@code ChatClientCustomizer}
 * — so the durable advisor is <em>not</em> attached automatically. It opts back in by injecting the
 * {@link DurableAdvisor} bean and adding it with {@code .defaultAdvisors(...)}, so the call still runs
 * as a durable Dapr Workflow (under the generic workflow name). Use this only when you can't build
 * from the Spring-managed {@code ChatClient.Builder}; otherwise prefer the injected builder.
 *
 * <p>Gated to the {@code dapr} profile: it needs a sidecar, and the {@link DurableAdvisor} bean exists
 * only when the durability layer is active.
 */
@Component
@Profile("dapr")
public class ManualDurabilityAgent {

    private static final String SYSTEM = """
            You are a flight fare checker. Use the searchFlights tool to look up options for the
            requested route and date, then report the cheapest fare and airline concisely.""";

    private final ChatClient chat;

    public ManualDurabilityAgent(ChatModel chatModel, DurableAdvisor durableAdvisor) {
        this.chat = ChatClient.builder(chatModel)   // static factory → customizer bypassed
                .defaultSystem(SYSTEM)
                .defaultTools(new FlightTools())
                .defaultAdvisors(durableAdvisor)     // opt back into durability by hand
                .build();
    }

    public String checkFare(String origin, String destination, String date) {
        return chat.prompt()
                .user("Find the cheapest flight from " + origin + " to " + destination + " on " + date + ".")
                .call()
                .content();
    }
}
