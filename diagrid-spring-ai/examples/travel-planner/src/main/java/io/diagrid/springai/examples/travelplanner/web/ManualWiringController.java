package io.diagrid.springai.examples.travelplanner.web;

import io.diagrid.springai.examples.travelplanner.agents.manual.DurableMemoryConcierge;
import io.diagrid.springai.examples.travelplanner.agents.manual.ManualDurabilityAgent;
import io.diagrid.springai.examples.travelplanner.agents.manual.ManualRegistryAgent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the three "manual wiring" agents ({@code agents/manual}): each demonstrates one
 * escape hatch — attaching the durable advisor by hand, registering a non-bean agent by hand, and
 * wiring Dapr-backed memory by hand.
 *
 * <p>Those agents are {@code @Profile}-gated (they depend on Dapr-only beans), so they may be absent.
 * The endpoints resolve them via {@link ObjectProvider} and return a hint naming the profile to
 * enable when the agent isn't active, rather than failing to start the controller.
 */
@RestController
public class ManualWiringController {

    private final ObjectProvider<ManualDurabilityAgent> durability;
    private final ObjectProvider<ManualRegistryAgent> registry;
    private final ObjectProvider<DurableMemoryConcierge> memory;

    public ManualWiringController(
            ObjectProvider<ManualDurabilityAgent> durability,
            ObjectProvider<ManualRegistryAgent> registry,
            ObjectProvider<DurableMemoryConcierge> memory) {
        this.durability = durability;
        this.registry = registry;
        this.memory = memory;
    }

    /** Manual durability: static builder + injected DurableAdvisor. Needs {@code --spring.profiles.active=dapr}. */
    @GetMapping("/manual/durability")
    public String durability(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam String date) {
        ManualDurabilityAgent agent = durability.getIfAvailable();
        return agent == null
                ? "ManualDurabilityAgent is inactive — start with --spring.profiles.active=dapr"
                : agent.checkFare(origin, destination, date);
    }

    /** Manual registry: AgentRegisteringAdvisor on a non-bean client. Needs {@code --spring.profiles.active=registry}. */
    @GetMapping("/manual/registry")
    public String registry(@RequestParam String nationality, @RequestParam String destination) {
        ManualRegistryAgent agent = registry.getIfAvailable();
        return agent == null
                ? "ManualRegistryAgent is inactive — start with --spring.profiles.active=registry"
                : agent.advise(nationality, destination);
    }

    /** Manual, Dapr-backed memory: explicit MessageWindowChatMemory. Needs {@code --spring.profiles.active=memory}. */
    @GetMapping("/manual/memory")
    public String memory(@RequestParam String conversationId, @RequestParam String message) {
        DurableMemoryConcierge agent = memory.getIfAvailable();
        return agent == null
                ? "DurableMemoryConcierge is inactive — start with --spring.profiles.active=memory"
                : agent.chat(conversationId, message);
    }
}
