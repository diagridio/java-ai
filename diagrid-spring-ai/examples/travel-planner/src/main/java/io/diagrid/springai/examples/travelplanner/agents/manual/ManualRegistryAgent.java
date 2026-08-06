package io.diagrid.springai.examples.travelplanner.agents.manual;

import io.diagrid.springai.registry.AgentRecordFactory;
import io.diagrid.springai.registry.AgentRegisteringAdvisor;
import io.diagrid.springai.registry.AgentRegistrar;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * <b>Manual registry.</b> Unlike {@code RegistryAgents} — which exposes each ChatClient as a
 * {@code @Bean} so the registry bean post-processor finds it — this agent holds its {@link ChatClient}
 * as a field inside a {@code @Component}, invisible to that post-processor. It registers itself by
 * attaching an {@link AgentRegisteringAdvisor} built from the injected {@link AgentRegistrar} and
 * {@link AgentRecordFactory}, under the explicit agent name {@code visaAdvisor}.
 *
 * <p>The client is still built from the injected {@link ChatClient.Builder}, so it is durable the
 * normal (automatic) way; only the registry hook is manual. Registration happens on the first call
 * (like the bean path's first-call enrichment). Gated to the {@code registry} profile, since the
 * registrar/factory beans exist only when the registry layer is active.
 */
@Component
@Profile("registry")
public class ManualRegistryAgent {

    private static final String SYSTEM = """
            You are a visa and entry-requirements advisor. Given a traveler's nationality and a
            destination country, summarize the likely visa requirement, typical permitted stay, and
            key documents. Always add a clear disclaimer to verify with official sources.""";

    private final ChatClient chat;

    public ManualRegistryAgent(
            ChatClient.Builder builder, AgentRegistrar registrar, AgentRecordFactory factory) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultAdvisors(new AgentRegisteringAdvisor("visaAdvisor", registrar, factory))
                .build();
    }

    public String advise(String nationality, String destination) {
        return chat.prompt()
                .user("Visa requirements for a " + nationality + " citizen traveling to " + destination + "?")
                .call()
                .content();
    }
}
