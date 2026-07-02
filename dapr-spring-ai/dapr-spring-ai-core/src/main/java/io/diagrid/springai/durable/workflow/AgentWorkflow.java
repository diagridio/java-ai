package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import io.dapr.durabletask.Task;
import io.dapr.workflows.Workflow;
import io.dapr.workflows.WorkflowContext;
import io.dapr.workflows.WorkflowStub;
import io.dapr.workflows.WorkflowTaskOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * The single, generic agent workflow: runs the chat-and-tools loop for one {@code ChatClient} call
 * until the model returns a turn with no tool calls, then completes with the final assistant text. The
 * loop is bounded by a configurable max-iterations cap ({@link #DEFAULT_MAX_ITERATIONS} by default);
 * if the model keeps requesting tools past it, the workflow fails rather than looping unbounded.
 *
 * <p>Deterministic control flow only. The conversation is reconstructed on every replay purely from
 * the seed (workflow input) plus the awaited activity results — no separate durable state. On replay
 * each {@code callActivity(...).await()} returns the persisted result rather than re-executing, so
 * the accumulated {@code conversation} list is rebuilt identically. All data formatting and the
 * model/tool I/O happen inside activities. A turn's tool calls are fanned out (scheduled together,
 * awaited with {@code ctx.allOf}) so they run concurrently; {@code allOf} preserves scheduling order,
 * so the reconstructed tool-response record is deterministic across replays.
 *
 * <p><b>One orchestrator, registered under several names.</b> This single generic orchestrator runs
 * the loop for every {@code ChatClient} call. It is registered under {@link #NAME} (the fallback used
 * by inline-built clients) and <em>additionally</em> under each {@code ChatClient} <b>bean name</b>,
 * so a bean-defined agent's calls run under a workflow named after the agent — like Dapr Agents'
 * per-agent workflow names, for readable grouping in tooling — while inline clients fall back to the
 * generic name. Which name a given call uses is chosen by the durable advisor attached to that client
 * (see {@code DurableAdvisor} / {@code DurableChatClientBeanPostProcessor}); durabletask requires
 * every name to be registered before scheduling, which the auto-configuration does at startup from
 * the ChatClient bean definitions. Agents/conversations remain distinguished by the <em>instance
 * id</em> (conversation-id keyed) — see {@code InstanceIdDerivation}.
 *
 * <p><b>Activity retries.</b> When constructed with a {@link WorkflowTaskOptions} carrying a retry
 * policy, every LLM and tool activity is scheduled with it, so a transient failure (provider rate
 * limit, network blip) is retried by the runtime instead of failing the whole workflow. The options
 * are fixed at construction and therefore constant across replays. The no-arg constructor disables
 * retries (one attempt). See {@code DaprSpringAiProperties.Retry}.
 */
public final class AgentWorkflow implements Workflow {

  // Generic (fallback) workflow name for ChatClients that are not beans. Contains ".workflow" so
  // tooling (e.g. the Catalyst dashboard) can list it; bean-defined agents use
  // spring-ai.{bean}.workflow instead (see DurableChatClientBeanPostProcessor). The worker registers
  // the orchestrator under this name explicitly (registerWorkflow(NAME, ...)), and scheduling uses
  // the same NAME — so it need not match the class name.
  public static final String NAME = "spring-ai.workflow";
  public static final String LLM_ACTIVITY = "dsa.llm.invoke";
  public static final String TOOL_ACTIVITY = "dsa.tool.invoke";

  /** Default cap on LLM turns per call when none is configured. */
  public static final int DEFAULT_MAX_ITERATIONS = 20;

  // Retry/backoff applied to every activity call, or null for no retry (single attempt). Final, so
  // it is constant across replays — it never changes the orchestration's deterministic shape.
  private final WorkflowTaskOptions activityOptions;

  // Hard cap on LLM turns before the loop gives up. Final → constant across replays.
  private final int maxIterations;

  /** Creates a workflow with no activity retries and the {@link #DEFAULT_MAX_ITERATIONS} cap. */
  public AgentWorkflow() {
    this(null, DEFAULT_MAX_ITERATIONS);
  }

  /**
   * @param activityOptions options (typically a retry policy) applied to every activity call, or
   *                        {@code null} to disable retries
   */
  public AgentWorkflow(WorkflowTaskOptions activityOptions) {
    this(activityOptions, DEFAULT_MAX_ITERATIONS);
  }

  /**
   * @param activityOptions options applied to every activity call, or {@code null} to disable retries
   * @param maxIterations   hard cap on LLM turns; the loop fails if the model still requests tools
   *                        after this many turns (guards against an unbounded tool loop)
   */
  public AgentWorkflow(WorkflowTaskOptions activityOptions, int maxIterations) {
    this.activityOptions = activityOptions;
    this.maxIterations = maxIterations;
  }

  @Override
  public WorkflowStub create() {
    return ctx -> {
      AgentRequest request = ctx.getInput(AgentRequest.class);

      // Seed with the input messages; each turn appends its records (decode-from-history).
      List<MessageRecord> conversation = new ArrayList<>(request.messages());
      ChatOptionsSpec options = request.options();
      List<LlmResult> turns = new ArrayList<>();

      while (true) {
        LlmActivityInput llmInput =
            new LlmActivityInput(List.copyOf(conversation), request.toolSpecs(), options);
        LlmResult llm = runActivity(ctx, LLM_ACTIVITY, llmInput, LlmResult.class);
        conversation.add(llm.toMessageRecord());
        turns.add(llm);

        if (!llm.hasToolCalls()) {
          ctx.complete(aggregate(turns));
          return;
        }

        // The model wants another tool round. Guard against an unbounded loop (runaway cost, history
        // growth, O(n^2) replay): fail once we've spent the configured budget of LLM turns.
        requireWithinIterationCap(turns.size(), maxIterations);

        // Fan-out/fan-in: schedule every tool call of this turn, then await them together, so the
        // tools of one turn run concurrently instead of serially. Task creation order is deterministic
        // (the model's tool-call order) and ctx.allOf preserves that order in its result list, so the
        // reconstructed TOOL record — and therefore replay — is identical. allOf throws
        // CompositeTaskFailedException if any tool fails, which fails the workflow just as a serial
        // await would (each task already carries the retry policy).
        List<Task<ToolResult>> toolTasks = new ArrayList<>(llm.toolCalls().size());
        for (ToolCallRecord call : llm.toolCalls()) {
          ToolActivityInput toolInput =
              new ToolActivityInput(call.id(), call.name(), call.arguments());
          toolTasks.add(callActivity(ctx, TOOL_ACTIVITY, toolInput, ToolResult.class));
        }
        List<ToolResult> batch = ctx.allOf(toolTasks).await();
        conversation.add(ToolResult.toMessageRecord(batch));
      }
    };
  }

  // Fails the workflow if the model still wants tools after the configured budget of LLM turns.
  // Deterministic: a pure function of the awaited-turn count and the constant cap, so replay-safe.
  static void requireWithinIterationCap(int llmTurnsSoFar, int maxIterations) {
    if (llmTurnsSoFar >= maxIterations) {
      throw new IllegalStateException(
          "Agent exceeded the maximum of " + maxIterations + " LLM iterations without completing; the "
              + "model kept requesting tools. Raise dapr.spring-ai.max-iterations if this is a "
              + "legitimate long tool chain, or investigate a misbehaving model/tool.");
    }
  }

  /**
   * Aggregates the awaited LLM turns into the workflow output: sums usage null-safely (turns with no
   * usage are skipped; if none report any, the aggregate is {@code null}), takes the finish reason and
   * model from the final turn, and counts the turns. A pure function of the activity outputs, so it is
   * deterministic across replays.
   */
  static AgentResult aggregate(List<LlmResult> turns) {
    Integer promptTokens = null;
    Integer completionTokens = null;
    Integer totalTokens = null;
    for (LlmResult turn : turns) {
      promptTokens = sum(promptTokens, turn.promptTokens());
      completionTokens = sum(completionTokens, turn.completionTokens());
      totalTokens = sum(totalTokens, turn.totalTokens());
    }
    TokenUsage usage =
        promptTokens == null && completionTokens == null && totalTokens == null
            ? null
            : new TokenUsage(promptTokens, completionTokens, totalTokens);
    LlmResult last = turns.get(turns.size() - 1);
    return new AgentResult(
        last.text(), last.finishReason(), usage, last.model(), last.responseId(), turns.size());
  }

  // Null-safe addition treating null as "absent": null + null = null; otherwise the present value(s).
  private static Integer sum(Integer a, Integer b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a + b;
  }

  // Schedule an activity as a Task with the configured retry options when present, plain otherwise.
  private <V> Task<V> callActivity(WorkflowContext ctx, String name, Object input, Class<V> returnType) {
    if (activityOptions != null) {
      return ctx.callActivity(name, input, activityOptions, returnType);
    }
    return ctx.callActivity(name, input, returnType);
  }

  // Schedule and immediately await a single activity (used for the LLM turn).
  private <V> V runActivity(WorkflowContext ctx, String name, Object input, Class<V> returnType) {
    return callActivity(ctx, name, input, returnType).await();
  }
}
