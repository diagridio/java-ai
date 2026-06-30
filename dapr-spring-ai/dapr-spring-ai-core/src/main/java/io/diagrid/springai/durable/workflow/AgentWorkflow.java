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
 * <p><b>One workflow type for every agent (by design).</b> Unlike Dapr Agents, which registers a
 * per-agent workflow ({@code dapr.<framework>.<agent>.workflow}), this layer registers a single
 * generic workflow ({@link #NAME}) for all {@code ChatClient} calls. Durability here is transparent
 * ChatClient interception with no agent-identity concept: the advisor wraps any ChatClient and runs
 * this same loop. Per-agent workflow names would require an opt-in agent name registered at startup
 * (durabletask needs the name registered before scheduling), which would also dent the zero-code
 * drop-in. Agents/conversations are instead distinguished by the <em>instance id</em>
 * (conversation-id keyed), not the workflow type — see {@code InstanceIdDerivation}.
 *
 * <p><b>Activity retries.</b> When constructed with a {@link WorkflowTaskOptions} carrying a retry
 * policy, every LLM and tool activity is scheduled with it, so a transient failure (provider rate
 * limit, network blip) is retried by the runtime instead of failing the whole workflow. The options
 * are fixed at construction and therefore constant across replays. The no-arg constructor disables
 * retries (one attempt). See {@code DaprSpringAiProperties.Retry}.
 */
public final class AgentWorkflow implements Workflow {

  // Must equal the name the workflow is registered under. registerWorkflow(AgentWorkflow.class)
  // registers under the canonical class name, so derive NAME from the class to prevent drift.
  public static final String NAME = AgentWorkflow.class.getName();
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
