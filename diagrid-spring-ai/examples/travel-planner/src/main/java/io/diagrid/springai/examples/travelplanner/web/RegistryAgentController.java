package io.diagrid.springai.examples.travelplanner.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers the two registry-format agents (the {@code packingAssistant} / {@code budgetAdvisor}
 * ChatClient beans). Both are registered with a thin record at startup; the first call here enriches
 * the record with the live system prompt and advertised tools (so {@code packingAssistant} gains its
 * getWeather tool). Inspect with the Diagrid dashboard, or in Redis:
 * {@code docker exec dapr_redis redis-cli KEYS '*agents*'}.
 */
@RestController
public class RegistryAgentController {

    private final ChatClient packingAssistant;
    private final ChatClient budgetAdvisor;

    public RegistryAgentController(@Qualifier("packingAssistant") ChatClient packingAssistant,
                                   @Qualifier("budgetAdvisor") ChatClient budgetAdvisor) {
        this.packingAssistant = packingAssistant;
        this.budgetAdvisor = budgetAdvisor;
    }

    /** Tool-backed agent: checks the weather, then suggests a packing list. */
    @GetMapping("/packing")
    public String packing(@RequestParam(defaultValue = "Paris") String city) {
        return packingAssistant.prompt()
                .user("What should I pack for a trip to " + city + "?")
                .call().content();
    }

    /** Tool-less agent: estimates a trip budget. */
    @GetMapping("/budget")
    public String budget(@RequestParam(defaultValue = "Paris") String city,
                         @RequestParam(defaultValue = "5") int days) {
        return budgetAdvisor.prompt()
                .user("Give me a budget for a " + days + "-day trip to " + city + ".")
                .call().content();
    }
}
