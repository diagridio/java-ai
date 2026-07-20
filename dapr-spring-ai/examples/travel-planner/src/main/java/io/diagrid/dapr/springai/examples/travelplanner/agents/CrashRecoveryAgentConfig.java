package io.diagrid.dapr.springai.examples.travelplanner.agents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines the crash-demo agent as a <b>named {@link ChatClient} bean</b> so it runs under its own
 * per-agent workflow name ({@code spring-ai.crashRecoveryAgent.workflow}) instead of the generic
 * {@code spring-ai.workflow}. The name is assigned at build/wiring time by the bean name.
 *
 * <p>It must be a {@code ChatClient} <em>bean</em> (not a {@code @Component} that holds a client): the
 * durability auto-config registers a per-agent workflow name on the in-process worker for every
 * ChatClient bean, and its bean post-processor attaches the matching per-agent durable advisor.
 * A {@code @Component}-held client only ever gets the generic name — and manually attaching a
 * differently-named advisor would schedule under a workflow name the worker never registered.
 *
 * <p>The booking tool ({@code SlowBookingTools}) is a global {@code @Tool} bean, so it is advertised
 * to this agent automatically (and survives a worker restart); this bean wires no tools of its own.
 * See {@code CrashRecoveryController}.
 */
@Configuration
public class CrashRecoveryAgentConfig {

    private static final String SYSTEM = """
            You are a travel booking assistant. Commit the customer's booking using the
            commitReservation tool and report the confirmation code it returns. Call the tool
            exactly once; never invent a confirmation code.""";

    @Bean
    ChatClient crashRecoveryAgent(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM).build();
    }
}
