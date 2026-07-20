package io.diagrid.dapr.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.dapr.durabletask.PropagatedHistory;
import io.dapr.workflows.WorkflowActivityContext;
import io.diagrid.dapr.springai.durable.tracing.DurableTracing;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * The tool activity puts the instance id + tool name in SLF4J MDC for the duration of the call and
 * restores prior values afterwards — including on exception. This works with {@link DurableTracing}
 * NOOP (no tracing backend needed), which is the point: log correlation regardless of tracing.
 */
class ToolInvokeActivityTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void setsInstanceIdAndToolNameInMdcDuringInvocationAndClearsAfter() {
    Map<String, String> seenDuringInvoke = new java.util.HashMap<>();
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new ToolSpec("getWeather", "d", "{}"),
        args -> {
          seenDuringInvoke.put(DurableTracing.KEY_INSTANCE_ID, MDC.get(DurableTracing.KEY_INSTANCE_ID));
          seenDuringInvoke.put(DurableTracing.KEY_TOOL_NAME, MDC.get(DurableTracing.KEY_TOOL_NAME));
          return "sunny";
        });
    ToolInvokeActivity activity = new ToolInvokeActivity(registry); // NOOP tracing

    ToolActivityInput input =
        new ToolActivityInput("c1", "getWeather", "{}", new ActivityTrace("inst-9", "wf", Map.of()));
    ToolResult result = (ToolResult) activity.run(contextOf(input));

    assertEquals("sunny", result.responseData());
    assertEquals("inst-9", seenDuringInvoke.get(DurableTracing.KEY_INSTANCE_ID), "instance id in MDC during invoke");
    assertEquals("getWeather", seenDuringInvoke.get(DurableTracing.KEY_TOOL_NAME), "tool name in MDC during invoke");

    assertNull(MDC.get(DurableTracing.KEY_INSTANCE_ID), "MDC instance id cleared after the activity");
    assertNull(MDC.get(DurableTracing.KEY_TOOL_NAME), "MDC tool name cleared after the activity");
  }

  @Test
  void clearsMdcEvenWhenTheToolThrows() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new ToolSpec("boom", "d", "{}"),
        args -> {
          throw new IllegalStateException("tool failed");
        });
    ToolInvokeActivity activity = new ToolInvokeActivity(registry);

    ToolActivityInput input =
        new ToolActivityInput("c1", "boom", "{}", new ActivityTrace("inst-9", "wf", Map.of()));

    assertThrows(RuntimeException.class, () -> activity.run(contextOf(input)));
    assertNull(MDC.get(DurableTracing.KEY_INSTANCE_ID), "MDC must be cleared even on failure");
    assertNull(MDC.get(DurableTracing.KEY_TOOL_NAME), "MDC must be cleared even on failure");
  }

  private static WorkflowActivityContext contextOf(ToolActivityInput input) {
    return new WorkflowActivityContext() {
      @Override
      public Logger getLogger() {
        return null;
      }

      @Override
      public String getName() {
        return "dsa.tool.invoke";
      }

      @Override
      public String getTaskExecutionId() {
        return "t1";
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> T getInput(Class<T> type) {
        return (T) input;
      }

      @Override
      public String getTraceParent() {
        return null;
      }

      @Override
      public Optional<PropagatedHistory> getPropagatedHistory() {
        return Optional.empty();
      }
    };
  }
}
