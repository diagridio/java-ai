package io.diagrid.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.testworker.CrashCoordinator;
import io.diagrid.springai.durable.testworker.WorkerMain;
import io.dapr.config.Properties;
import io.dapr.testcontainers.Component;
import io.dapr.testcontainers.DaprContainer;
import io.dapr.testcontainers.DaprLogLevel;
import io.dapr.workflows.client.DaprWorkflowClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Phase 1 gate: prove a durable {@code ChatClient}-style call survives a worker-JVM crash.
 *
 * <p>The Dapr sidecar (and thus all workflow state) runs in a Testcontainers container and outlives
 * the worker. A separate worker JVM runs the workflow + activities against a real local Ollama. The
 * single tool coordinates a deterministic crash: it signals readiness and blocks before its side
 * effect, the test SIGKILLs the worker there, then restarts it. On recovery the completed LLM
 * activity is NOT re-run and the tool's side effect happens exactly once.
 *
 * <p>Requires Docker and a local Ollama with the model; tagged {@code integration} and excluded from
 * the default build. Run with: {@code mvn -pl core test -Dtest=CrashRecoveryIT -DexcludedGroups=}.
 */
@Testcontainers
@Tag("integration")
class CrashRecoveryIT {

  private static final String OLLAMA_BASE_URL = "http://localhost:11434";
  private static final String MODEL = "llama3.1:8b";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  private static final DaprContainer DAPR =
      new DaprContainer("daprio/daprd:1.18.0")
          .withAppName("dsa-crash-it")
          .withNetwork(NETWORK)
          .withComponent(
              new Component(
                  "kvstore", "state.in-memory", "v1", Map.of("actorStateStore", "true")))
          .withDaprLogLevel(DaprLogLevel.INFO)
          .withLogConsumer(frame -> System.out.print("[daprd] " + frame.getUtf8String()));

  private DaprWorkflowClient workflowClient;
  private DurableRunner runner;
  private Path testDir;
  private CrashCoordinator coord;

  @BeforeEach
  void setUp() throws IOException {
    assumeTrue(ollamaReady(), "Ollama not reachable at " + OLLAMA_BASE_URL + "; skipping");
    workflowClient =
        new DaprWorkflowClient(new Properties(Map.of("dapr.grpc.endpoint", DAPR.getGrpcEndpoint())));
    runner = new DurableRunner(workflowClient, new InstanceIdDerivation(), Duration.ofSeconds(180));
    testDir = Files.createTempDirectory("dsa-crash-it");
    coord = new CrashCoordinator(testDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (workflowClient != null) {
      workflowClient.close();
    }
  }

  @Test
  void baselineRunCompletesWithoutCrash() throws Exception {
    // Pre-set killDone so the tool never blocks/crashes: a clean happy-path reference.
    coord.signal("killDone");

    Process worker = startWorker(testDir, "baseline");
    try {
      AgentResult result = runner.run(request("baseline: what is the secret word?"));
      assertTrue(
          result.finalText().contains("BANANA"), "expected secret word in result, got: " + result);
      assertEquals(1, coord.count("toolSideEffect"), "tool side effect should run exactly once");
      assertEquals(1, coord.count("toolAttempt"), "tool attempted once with no crash");
      assertEquals(2, coord.count("llmExec"), "two LLM turns: tool-call then final answer");
    } finally {
      worker.destroyForcibly();
      worker.waitFor();
    }
  }

  @Test
  void survivesWorkerCrashBetweenLlmAndTool() throws Exception {
    AgentRequest request = request("what is the secret word?");
    AtomicReference<AgentResult> result = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();

    // Worker #1 — will be SIGKILLed mid-tool.
    Process worker1 = startWorker(testDir, "crash-1");

    // Drive the durable call on a background thread; it blocks until the workflow completes.
    CompletableFuture<Void> driver =
        CompletableFuture.runAsync(
            () -> {
              try {
                result.set(runner.run(request));
              } catch (Exception e) {
                failure.set(e);
              }
            });

    // The tool signals readiness right before its side effect: kill the worker there.
    assertTrue(
        coord.awaitSignal("readyToKill", Duration.ofSeconds(120)),
        "tool never reached the pre-side-effect point");
    assertEquals(0, coord.count("toolSideEffect"), "side effect must NOT have run before the crash");

    worker1.destroyForcibly(); // SIGKILL on Unix — no graceful shutdown
    worker1.waitFor();

    // Tell the recovered tool execution to proceed, then bring a fresh worker JVM back up.
    coord.signal("killDone");
    Process worker2 = startWorker(testDir, "crash-2");
    try {
      driver.get(180, java.util.concurrent.TimeUnit.SECONDS);
      assertNotNull(failure.get() == null ? result.get() : null, "driver failed: " + failure.get());

      assertTrue(
          result.get().finalText().contains("BANANA"), "expected secret word, got: " + result.get());
      // Exactly-once side effect despite the crash:
      assertEquals(1, coord.count("toolSideEffect"), "tool side effect must run exactly once");
      // Tool was attempted twice (pre-crash + recovery), proving the interrupted activity re-ran:
      assertEquals(2, coord.count("toolAttempt"), "tool attempted pre-crash and on recovery");
      // The completed LLM turn-1 activity was NOT re-run on replay (would be 3 if it were):
      assertEquals(2, coord.count("llmExec"), "completed LLM activity must not be re-executed");
    } finally {
      worker2.destroyForcibly();
      worker2.waitFor();
    }
  }

  @Test
  @Disabled(
      "One-time backend-behavior probe, already answered: scheduling a duplicate active instanceId"
          + " throws StatusRuntimeException '...an active workflow with ID ... already exists'."
          + " DurableRunner relies on this (schedule-then-catch-already-exists). Disabled because it"
          + " schedules with no worker, leaving a PENDING instance that makes the sidecar spin on"
          + " DaprBuiltInActorNotFoundRetries and pollutes the shared static container.")
  void duplicateScheduleWithSameInstanceIdBehavior() throws Exception {
    // Empirically records what the backend does when the same instance id is scheduled twice
    // (the schedule-then-catch collision path). No worker is running, so neither will execute; we
    // only observe whether the second schedule throws.
    AgentRequest request = request("duplicate-id probe");
    String instanceId = runner.instanceId(request);
    workflowClient.scheduleNewWorkflow(AgentWorkflow.NAME, request, instanceId);

    String outcome;
    try {
      workflowClient.scheduleNewWorkflow(AgentWorkflow.NAME, request, instanceId);
      outcome = "second schedule SUCCEEDED (backend tolerated duplicate id)";
    } catch (RuntimeException e) {
      outcome = "second schedule THREW: " + e.getClass().getName() + ": " + e.getMessage();
    }
    System.out.println("[duplicate-id probe] " + outcome);
    workflowClient.terminateWorkflow(instanceId, null);
  }

  private AgentRequest request(String userText) {
    return new AgentRequest(
        List.of(
            MessageRecord.system(
                "You can call the getSecretWord tool, which takes no arguments and returns a secret"
                    + " word. Call it exactly once, then reply with one sentence containing that"
                    + " exact word. Do not call the tool again after it returns."),
            MessageRecord.user(userText)),
        List.of(
            new ToolSpec(
                "getSecretWord",
                "Returns the secret word. Takes no arguments.",
                "{\"type\":\"object\",\"properties\":{}}")),
        new ChatOptionsSpec(MODEL, 0.0, null, null, null, null, null, null));
  }

  private Process startWorker(Path dir, String label) throws IOException {
    String java = System.getProperty("java.home") + "/bin/java";
    String classpath = System.getProperty("java.class.path");
    ProcessBuilder pb =
        new ProcessBuilder(java, "-cp", classpath, WorkerMain.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("worker-" + label + ".log").toFile());
    pb.environment().put("DAPR_GRPC_ENDPOINT", DAPR.getGrpcEndpoint());
    pb.environment().put("DSA_TEST_DIR", dir.toString());
    pb.environment().put("OLLAMA_BASE_URL", OLLAMA_BASE_URL);
    pb.environment().put("OLLAMA_MODEL", MODEL);
    Process process = pb.start();
    awaitWorkerReady(dir.resolve("worker-" + label + ".log"), process);
    return process;
  }

  private void awaitWorkerReady(Path logFile, Process process) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new IllegalStateException("Worker exited before becoming ready; see " + logFile);
      }
      try {
        if (Files.exists(logFile) && Files.readString(logFile).contains("WORKER_READY")) {
          return;
        }
        Thread.sleep(200);
      } catch (IOException e) {
        // log not readable yet
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new IllegalStateException("Worker did not report WORKER_READY in time; see " + logFile);
  }

  private static boolean ollamaReady() {
    try {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(OLLAMA_BASE_URL + "/api/tags")).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 && response.body().contains(MODEL);
    } catch (Exception e) {
      return false;
    }
  }
}
