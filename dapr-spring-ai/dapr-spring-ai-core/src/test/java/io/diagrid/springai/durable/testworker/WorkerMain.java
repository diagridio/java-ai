package io.diagrid.springai.durable.testworker;

import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.diagrid.springai.durable.workflow.LlmInvokeActivity;
import io.diagrid.springai.durable.workflow.ToolInvokeActivity;
import io.diagrid.springai.durable.workflow.ToolRegistry;
import io.dapr.workflows.WorkflowActivity;
import io.dapr.workflows.WorkflowActivityContext;
import io.dapr.workflows.runtime.WorkflowRuntime;
import io.dapr.workflows.runtime.WorkflowRuntimeBuilder;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * Separate-JVM workflow worker for the crash-recovery integration test. Connects to the Dapr sidecar
 * via {@code DAPR_GRPC_ENDPOINT}, registers the generic {@link AgentWorkflow} plus the LLM and tool
 * activities (backed by a real Ollama model), then stays alive until SIGKILLed by the test.
 *
 * <p>The LLM activity is wrapped to count real executions (proving completed activities are not
 * re-run on replay). The single tool, {@code getSecretWord}, coordinates the crash via
 * {@link CrashCoordinator}: on its first attempt it signals readiness and waits — the test SIGKILLs
 * the worker here, before the side effect — and on recovery it performs its side effect exactly once.
 */
public final class WorkerMain {

  public static void main(String[] args) throws Exception {
    String ollamaBaseUrl = envOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    String model = envOrDefault("OLLAMA_MODEL", "llama3.1:8b");
    CrashCoordinator coord = CrashCoordinator.fromEnv();

    OllamaChatModel chatModel =
        OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(ollamaBaseUrl).build())
            .build();

    LlmInvokeActivity llmDelegate = new LlmInvokeActivity(chatModel, new OllamaChatOptionsFactory());
    WorkflowActivity countingLlm =
        ctx -> {
          coord.tick("llmExec");
          return llmDelegate.run(ctx);
        };

    ToolRegistry registry =
        new ToolRegistry().register("getSecretWord", arguments -> getSecretWord(coord));
    ToolInvokeActivity toolActivity = new ToolInvokeActivity(registry);

    WorkflowRuntimeBuilder builder =
        new WorkflowRuntimeBuilder()
            // Register the orchestrator under AgentWorkflow.NAME explicitly so the client's
            // scheduleNewWorkflow(NAME, ...) matches (NAME is no longer the class name).
            .registerWorkflow(AgentWorkflow.NAME, AgentWorkflow.class)
            .registerActivity(AgentWorkflow.LLM_ACTIVITY, (WorkflowActivity) countingLlm)
            .registerActivity(AgentWorkflow.TOOL_ACTIVITY, toolActivity);

    try (WorkflowRuntime runtime = builder.build()) {
      runtime.start(false);
      // Signal the test that this worker is connected and ready to take work.
      System.out.println("WORKER_READY model=" + model + " ollama=" + ollamaBaseUrl);
      System.out.flush();
      // Stay alive until SIGKILLed (or restarted) by the test.
      new CountDownLatch(1).await();
    }
  }

  /**
   * The single durable tool. On the first attempt (before any crash) it signals readiness and waits,
   * giving the test a deterministic window to SIGKILL the worker before the side effect runs. On
   * recovery the {@code killDone} signal is set, so it skips the wait and performs the side effect
   * exactly once.
   */
  private static String getSecretWord(CrashCoordinator coord) {
    coord.tick("toolAttempt");
    if (!coord.isSignalled("killDone")) {
      coord.signal("readyToKill");
      // The test SIGKILLs during this wait; if it does not (defensive), proceed once killDone is set.
      coord.awaitSignal("killDone", Duration.ofSeconds(120));
    }
    coord.tick("toolSideEffect");
    return "BANANA";
  }

  private static String envOrDefault(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
