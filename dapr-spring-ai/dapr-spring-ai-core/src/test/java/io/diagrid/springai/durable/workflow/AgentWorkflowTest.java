package io.diagrid.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workflow output is a pure aggregation of the awaited LLM turns (replay-safe by construction).
 * These cover {@link AgentWorkflow#aggregate} directly: usage sums null-safely, the finish reason and
 * model come from the final turn, and the turn count is exact.
 */
class AgentWorkflowTest {

  private static LlmResult turn(
      String text, String finish, Integer prompt, Integer completion, Integer total, String model) {
    return new LlmResult(text, List.of(), finish, prompt, completion, total, model, null);
  }

  @Test
  void sumsUsageAndTakesFinalTurnMetadata() {
    AgentResult result =
        AgentWorkflow.aggregate(
            List.of(
                turn("", "tool_calls", 100, 20, 120, "gpt-4o-mini"),
                turn("done", "stop", 50, 30, 80, "gpt-4o")));

    assertEquals("done", result.finalText());
    assertEquals("stop", result.finishReason(), "finish reason comes from the final turn");
    assertEquals("gpt-4o", result.model(), "model comes from the final turn");
    assertEquals(2, result.turns());
    assertEquals(150, result.aggregatedUsage().promptTokens().intValue());
    assertEquals(50, result.aggregatedUsage().completionTokens().intValue());
    assertEquals(200, result.aggregatedUsage().totalTokens().intValue());
  }

  @Test
  void skipsTurnsWithoutUsageButStillSumsTheRest() {
    AgentResult result =
        AgentWorkflow.aggregate(
            List.of(
                turn("", "tool_calls", null, null, null, "m"), // this turn reported no usage
                turn("done", "stop", 10, 5, 15, "m")));

    assertEquals(10, result.aggregatedUsage().promptTokens().intValue());
    assertEquals(5, result.aggregatedUsage().completionTokens().intValue());
    assertEquals(15, result.aggregatedUsage().totalTokens().intValue());
  }

  @Test
  void nullAggregateWhenNoTurnReportsUsage() {
    AgentResult result =
        AgentWorkflow.aggregate(List.of(turn("hi", "stop", null, null, null, null)));

    assertNull(result.aggregatedUsage(), "no usage reported anywhere → null aggregate");
    assertEquals(1, result.turns());
    assertEquals("hi", result.finalText());
  }
}
