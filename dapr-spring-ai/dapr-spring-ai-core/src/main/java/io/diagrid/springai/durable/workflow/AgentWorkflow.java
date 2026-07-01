package io.diagrid.springai.durable.workflow;

import io.diagrid.springai.durable.conversation.MessageRecord;
import io.diagrid.springai.durable.conversation.ToolCallRecord;
import io.dapr.workflows.Workflow;
import io.dapr.workflows.WorkflowContext;
import io.dapr.workflows.WorkflowStub;
import io.dapr.workflows.WorkflowTaskOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * The single, generic agent workflow: runs the chat-and-tools loop for one {@code ChatClient} call
 * until the model returns a turn with no tool calls, then completes with the final assistant text.
 *
 * <p>Deterministic control flow only. The conversation is reconstructed on every replay purely from
 * the seed (workflow input) plus the awaited activity results — no separate durable state. On replay
 * each {@code callActivity(...).await()} returns the persisted result rather than re-executing, so
 * the accumulated {@code conversation} list is rebuilt identically. All data formatting and the
 * model/tool I/O happen inside activities.
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

  // Generic (fallback) workflow name for ChatClients that are not beans, in the Dapr Agents shape
  // (contains ".workflow"). Bean-defined agents use dapr.spring-ai.{bean}.workflow instead (see
  // DurableChatClientBeanPostProcessor). The worker registers the orchestrator under this name
  // explicitly (registerWorkflow(NAME, ...)), and scheduling uses the same NAME — so it need not
  // match the class name.
  public static final String NAME = "dapr.spring-ai.workflow";
  public static final String LLM_ACTIVITY = "dsa.llm.invoke";
  public static final String TOOL_ACTIVITY = "dsa.tool.invoke";

  // Retry/backoff applied to every activity call, or null for no retry (single attempt). Final, so
  // it is constant across replays — it never changes the orchestration's deterministic shape.
  private final WorkflowTaskOptions activityOptions;

  /** Creates a workflow whose activities are not retried (a single attempt each). */
  public AgentWorkflow() {
    this(null);
  }

  /**
   * @param activityOptions options (typically a retry policy) applied to every activity call, or
   *                        {@code null} to disable retries
   */
  public AgentWorkflow(WorkflowTaskOptions activityOptions) {
    this.activityOptions = activityOptions;
  }

  @Override
  public WorkflowStub create() {
    return ctx -> {
      AgentRequest request = ctx.getInput(AgentRequest.class);

      // Seed with the input messages; each turn appends its records (decode-from-history).
      List<MessageRecord> conversation = new ArrayList<>(request.messages());
      ChatOptionsSpec options = request.options();

      while (true) {
        LlmActivityInput llmInput =
            new LlmActivityInput(List.copyOf(conversation), request.toolSpecs(), options);
        LlmResult llm = runActivity(ctx, LLM_ACTIVITY, llmInput, LlmResult.class);
        conversation.add(llm.toMessageRecord());

        if (!llm.hasToolCalls()) {
          ctx.complete(llm.text());
          return;
        }

        List<ToolResult> batch = new ArrayList<>(llm.toolCalls().size());
        for (ToolCallRecord call : llm.toolCalls()) {
          ToolActivityInput toolInput =
              new ToolActivityInput(call.id(), call.name(), call.arguments());
          ToolResult result = runActivity(ctx, TOOL_ACTIVITY, toolInput, ToolResult.class);
          batch.add(result);
        }
        conversation.add(ToolResult.toMessageRecord(batch));
      }
    };
  }

  // Schedule an activity with the configured retry options when present, plain otherwise.
  private <V> V runActivity(WorkflowContext ctx, String name, Object input, Class<V> returnType) {
    if (activityOptions != null) {
      return ctx.callActivity(name, input, activityOptions, returnType).await();
    }
    return ctx.callActivity(name, input, returnType).await();
  }
}
