package io.diagrid.springai.examples.travelplanner.registry;

import io.diagrid.springai.examples.travelplanner.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two agents defined in the registry-friendly "ChatClient bean" format.
 *
 * <p>Unlike the other agents in this module — which build their {@link ChatClient} inline inside a
 * {@code @Component} and are therefore invisible to the agent registry — each ChatClient here is a
 * Spring {@code @Bean}, which the {@code dapr-spring-ai-agent-registry} discovers. Registration is
 * two-phase: at startup a thin record (name, app id, resolved model) is written for every ChatClient
 * bean, so the agents appear immediately; the first call then enriches the record with the live
 * system prompt and advertised tools. The bean <em>name</em> ({@code packingAssistant} /
 * {@code budgetAdvisor}) is the agent's registry identity.
 *
 * <p>Both are built from the injected {@link ChatClient.Builder}, so the durability advisor still
 * applies: these agents run as durable Dapr Workflows <em>and</em> register themselves. One
 * advertises a tool and the other none, so the registry records show the tool list it captures.
 */
@Configuration
public class RegistryAgents {

    /** Tool-backed agent — its registry record advertises the getWeather tool. */
    @Bean
    ChatClient packingAssistant(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are a packing assistant. Given a destination, check the weather with the
                        getWeather tool and recommend a concise packing list suited to the conditions.""")
                .defaultTools(new WeatherTools())
                .build();
    }

    /** Tool-less agent — its registry record advertises no tools. */
    @Bean
    ChatClient budgetAdvisor(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are a travel budget advisor. Given a destination and trip length, give a
                        brief, realistic daily budget breakdown (lodging, food, transport, activities)
                        and a total estimate. Keep it concise.""")
                .build();
    }
}
