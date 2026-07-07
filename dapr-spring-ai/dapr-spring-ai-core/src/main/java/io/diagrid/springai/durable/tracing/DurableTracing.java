package io.diagrid.springai.durable.tracing;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Core SPI for end-to-end tracing of the durable path, kept free of any Micrometer/OpenTelemetry
 * dependency so {@code dapr-spring-ai-core} builds and runs standalone. The starter supplies a
 * Micrometer-backed implementation; absent one, {@link #NOOP} makes every hook a passthrough with
 * zero overhead beyond a null check.
 *
 * <p>Two hooks, mirroring where real work happens (never in the replayed orchestrator):
 * <ul>
 *   <li><b>Caller thread</b> — {@link #observeDurableCall} wraps the blocking schedule+wait in an
 *       observation and hands the work a <em>propagation carrier</em> (W3C {@code traceparent} etc.)
 *       captured within the observation's scope. Carrying that carrier through the workflow input is
 *       what lets activity spans on worker threads (possibly another replica) nest under the
 *       originating request's trace — the dapr-agents propagation model, done Java-side.</li>
 *   <li><b>Worker thread</b> — {@link #runInActivityScope} restores the parent context from the
 *       carrier and runs the activity body under a child span, so Spring AI's own ChatModel
 *       (gen_ai) observation parents correctly for free.</li>
 * </ul>
 */
public interface DurableTracing {

  /** Caller-side observation name for one durable {@code ChatClient} call. */
  String CALL_OBSERVATION = "dapr.springai.durable.call";
  /** Activity span name for a model turn. */
  String LLM_SPAN = "dapr.springai.llm.invoke";
  /** Activity span name for a tool call. */
  String TOOL_SPAN = "dapr.springai.tool.invoke";

  /** Attribute / SLF4J MDC key for the workflow instance id (high cardinality). */
  String KEY_INSTANCE_ID = "dapr.springai.instance_id";
  /** Attribute key for the workflow name (low cardinality). */
  String KEY_WORKFLOW_NAME = "dapr.springai.workflow_name";
  /** Attribute / MDC key for the tool name in the tool activity. */
  String KEY_TOOL_NAME = "dapr.springai.tool_name";
  /** Low-cardinality outcome attribute on the caller observation: completed | timeout | failed. */
  String KEY_OUTCOME = "dapr.springai.outcome";

  /**
   * Runs {@code call} as an observed durable call on the caller thread. The implementation starts an
   * observation (tagged with the workflow name + instance id), opens its scope, and passes {@code call}
   * a propagation carrier captured within that scope; it records the outcome from how {@code call}
   * returns or throws. Exceptions propagate unchanged — the observation must not alter the exception
   * contract (a {@code DurableCallTimeoutException} still surfaces typed and unwrapped).
   *
   * @param workflowName workflow name (low-cardinality tag)
   * @param instanceId   workflow instance id (high-cardinality tag)
   * @param call         the schedule+wait work; receives the carrier to embed in the workflow input
   */
  <T> T observeDurableCall(String workflowName, String instanceId, Function<Map<String, String>, T> call);

  /**
   * Runs an activity body under a child span whose parent is restored from {@code carrier}. With the
   * context restored, downstream observations (e.g. Spring AI's ChatModel gen_ai span) parent
   * correctly. An empty carrier or absent tracing backend degrades to running {@code work} directly.
   *
   * @param spanName   activity span name
   * @param carrier    propagation carrier from the workflow input (may be empty)
   * @param attributes span attributes (instance id, workflow name, tool name, …)
   * @param work       the activity body
   */
  <T> T runInActivityScope(
      String spanName, Map<String, String> carrier, Map<String, String> attributes, Supplier<T> work);

  /** No tracing: hooks are passthroughs. Used when no observability backend is configured. */
  DurableTracing NOOP =
      new DurableTracing() {
        @Override
        public <T> T observeDurableCall(
            String workflowName, String instanceId, Function<Map<String, String>, T> call) {
          return call.apply(Map.of());
        }

        @Override
        public <T> T runInActivityScope(
            String spanName, Map<String, String> carrier, Map<String, String> attributes, Supplier<T> work) {
          return work.get();
        }
      };
}
