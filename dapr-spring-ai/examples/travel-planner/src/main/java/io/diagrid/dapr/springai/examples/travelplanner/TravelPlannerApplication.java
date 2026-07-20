package io.diagrid.dapr.springai.examples.travelplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Travel Planner built on Spring AI 2.0.
 *
 * <p>Each agent is a {@code ChatClient} configured with a role-specific system prompt and a set of
 * {@code @Tool} methods; Spring AI's tool-calling advisor runs the ReAct loop automatically.
 * Multi-agent orchestration (sequential, parallel, loop, conditional, nested) is composed at the
 * application layer in {@link io.diagrid.dapr.springai.examples.travelplanner.orchestration.TravelOrchestrationService},
 * mirroring Spring AI's documented agentic patterns. No Dapr — pure Spring AI + OpenAI.
 */
@SpringBootApplication
public class TravelPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelPlannerApplication.class, args);
    }
}
