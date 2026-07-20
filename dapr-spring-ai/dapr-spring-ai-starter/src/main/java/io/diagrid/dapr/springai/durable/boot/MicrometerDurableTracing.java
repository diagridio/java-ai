package io.diagrid.dapr.springai.durable.boot;

import io.diagrid.dapr.springai.durable.client.DurableCallTimeoutException;
import io.diagrid.dapr.springai.durable.tracing.DurableTracing;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Micrometer-backed {@link DurableTracing}. Uses the always-present {@link ObservationRegistry} for
 * the caller-side observation, and the (optional) {@link Tracer}/{@link Propagator} for cross-thread
 * span propagation. Degrades gracefully: no {@code Tracer}/{@code Propagator} bean ⇒ the observation
 * still records (attributes/metrics) but there is no span propagation; the class itself loads only
 * when micrometer-tracing is on the classpath (its bean is {@code @ConditionalOnClass(Tracer.class)}),
 * otherwise the core {@link DurableTracing#NOOP} is used.
 */
final class MicrometerDurableTracing implements DurableTracing {

  private final ObservationRegistry observationRegistry;
  private final Tracer tracer; // nullable: no tracing bridge configured
  private final Propagator propagator; // nullable

  MicrometerDurableTracing(ObservationRegistry observationRegistry, Tracer tracer, Propagator propagator) {
    this.observationRegistry = observationRegistry;
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public <T> T observeDurableCall(
      String workflowName, String instanceId, Function<Map<String, String>, T> call) {
    Observation observation =
        Observation.createNotStarted(CALL_OBSERVATION, observationRegistry)
            .lowCardinalityKeyValue(KEY_WORKFLOW_NAME, orNone(workflowName))
            .highCardinalityKeyValue(KEY_INSTANCE_ID, orNone(instanceId));
    observation.start();
    String outcome = "completed";
    // Scope managed manually (not try-with-resources) so the handle is referenced — PMD flags an
    // unused try-with-resources variable otherwise.
    Observation.Scope scope = observation.openScope();
    try {
      // Capture inside the scope so the carrier reflects THIS observation's span.
      return call.apply(capture());
    } catch (DurableCallTimeoutException e) {
      outcome = "timeout"; // not a failure — the workflow is still running
      observation.error(e);
      throw e;
    } catch (RuntimeException e) {
      outcome = "failed";
      observation.error(e);
      throw e;
    } finally {
      scope.close();
      observation.lowCardinalityKeyValue(KEY_OUTCOME, outcome);
      observation.stop();
    }
  }

  // Inject the current trace context into a fresh W3C carrier; empty when there is no tracing backend.
  private Map<String, String> capture() {
    if (tracer == null || propagator == null) {
      return Map.of();
    }
    TraceContext current = tracer.currentTraceContext().context();
    if (current == null) {
      return Map.of();
    }
    Map<String, String> carrier = new LinkedHashMap<>();
    propagator.inject(current, carrier, Map::put);
    return carrier;
  }

  @Override
  public <T> T runInActivityScope(
      String spanName, Map<String, String> carrier, Map<String, String> attributes, Supplier<T> work) {
    if (tracer == null || propagator == null) {
      return work.get();
    }
    // Restore the parent from the carrier (empty carrier ⇒ a root span); Spring AI's ChatModel
    // observation then parents under this span for free. Keep the carrier typed as <String,String>
    // (a `?:` with Map.of() would widen it and break the Getter type inference).
    Map<String, String> safeCarrier = carrier == null ? Map.of() : carrier;
    Span span = propagator.extract(safeCarrier, Map::get).name(spanName).start();
    attributes.forEach(span::tag);
    // Scope managed manually (see observeDurableCall) so the handle is referenced.
    Tracer.SpanInScope inScope = tracer.withSpan(span);
    try {
      return work.get();
    } catch (RuntimeException e) {
      span.error(e);
      throw e;
    } finally {
      inScope.close();
      span.end();
    }
  }

  private static String orNone(String value) {
    return value == null || value.isBlank() ? "none" : value;
  }
}
