package io.diagrid.dapr.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.diagrid.dapr.springai.durable.client.DurableCallTimeoutException;
import io.diagrid.dapr.springai.durable.tracing.DurableTracing;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two payoffs of {@link MicrometerDurableTracing}: the caller observation records the
 * outcome (and a {@link DurableCallTimeoutException} still propagates typed/unwrapped), and the
 * activity scope parents a child span under the caller's span via the captured carrier — the
 * cross-thread trace continuity that is the whole point.
 */
class MicrometerDurableTracingTest {

  @Test
  void observationRecordsCompletedOutcome() {
    TestObservationRegistry registry = TestObservationRegistry.create();
    MicrometerDurableTracing sut = new MicrometerDurableTracing(registry, null, null);

    assertEquals("ok", sut.observeDurableCall("wf", "inst-1", carrier -> "ok"));

    TestObservationRegistryAssert.assertThat(registry)
        .hasObservationWithNameEqualTo(DurableTracing.CALL_OBSERVATION)
        .that()
        .hasLowCardinalityKeyValue(DurableTracing.KEY_OUTCOME, "completed")
        .hasLowCardinalityKeyValue(DurableTracing.KEY_WORKFLOW_NAME, "wf");
  }

  @Test
  void timeoutRecordsTimeoutOutcomeAndPropagatesUnwrapped() {
    TestObservationRegistry registry = TestObservationRegistry.create();
    MicrometerDurableTracing sut = new MicrometerDurableTracing(registry, null, null);

    DurableCallTimeoutException thrown =
        assertThrows(
            DurableCallTimeoutException.class,
            () ->
                sut.observeDurableCall(
                    "wf",
                    "inst-1",
                    carrier -> {
                      throw new DurableCallTimeoutException("inst-1", "wf", null);
                    }));

    assertEquals("inst-1", thrown.instanceId(), "the typed timeout must reach the caller unwrapped");
    TestObservationRegistryAssert.assertThat(registry)
        .hasObservationWithNameEqualTo(DurableTracing.CALL_OBSERVATION)
        .that()
        .hasLowCardinalityKeyValue(DurableTracing.KEY_OUTCOME, "timeout");
  }

  @Test
  void activityScopeParentsUnderTheCallersSpan() {
    SimpleTracer tracer = new SimpleTracer();
    Propagator propagator = new TestPropagator(tracer);
    MicrometerDurableTracing sut = new MicrometerDurableTracing(ObservationRegistry.NOOP, tracer, propagator);

    // Caller thread: a span is current; observeDurableCall captures the carrier within its scope.
    Span caller = tracer.nextSpan().name("caller").start();
    AtomicReference<Map<String, String>> carrier = new AtomicReference<>();
    try (Tracer.SpanInScope inScope = tracer.withSpan(caller)) {
      sut.observeDurableCall(
          "wf",
          "inst-1",
          c -> {
            carrier.set(c);
            return "x";
          });
    }
    assertFalse(carrier.get().isEmpty(), "capture within the caller scope must produce a carrier");
    assertEquals(caller.context().traceId(), carrier.get().get("traceId"));

    // Worker thread (simulated): restore the context + create the activity's child span.
    AtomicReference<Span> child = new AtomicReference<>();
    sut.runInActivityScope(
        DurableTracing.LLM_SPAN,
        carrier.get(),
        Map.of(DurableTracing.KEY_INSTANCE_ID, "inst-1"),
        () -> {
          child.set(tracer.currentSpan());
          return "done";
        });

    assertEquals(
        caller.context().traceId(), child.get().context().traceId(), "child runs in the caller's trace");
    assertEquals(
        caller.context().spanId(), child.get().context().parentId(), "child is parented to the caller span");
  }

  /** Minimal W3C-ish propagator over SimpleTracer contexts — enough to prove capture↔restore symmetry. */
  private static final class TestPropagator implements Propagator {
    private final Tracer tracer;

    TestPropagator(Tracer tracer) {
      this.tracer = tracer;
    }

    @Override
    public List<String> fields() {
      return List.of("traceId", "spanId");
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
      setter.set(carrier, "traceId", context.traceId());
      setter.set(carrier, "spanId", context.spanId());
    }

    @Override
    public <C> Span.Builder extract(C carrier, Getter<C> getter) {
      SimpleTraceContext parent = new SimpleTraceContext();
      parent.setTraceId(getter.get(carrier, "traceId"));
      parent.setSpanId(getter.get(carrier, "spanId"));
      return tracer.spanBuilder().setParent(parent);
    }
  }
}
