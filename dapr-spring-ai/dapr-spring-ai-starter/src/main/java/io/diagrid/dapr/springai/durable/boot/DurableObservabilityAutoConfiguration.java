package io.diagrid.dapr.springai.durable.boot;

import io.diagrid.dapr.springai.durable.tracing.DurableTracing;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires the Micrometer implementation of {@link DurableTracing} into the durable path.
 *
 * <p>Gated on micrometer-tracing being on the classpath ({@link Tracer}); it is an <b>optional</b>
 * dependency of the starter, so an app without a tracing bridge never loads this and the durable
 * beans fall back to {@link DurableTracing#NOOP} (zero overhead). When present, the impl still
 * degrades gracefully: the {@link ObservationRegistry} always exists in Boot, while the
 * {@link Tracer}/{@link Propagator} beans exist only when a tracing bridge is actually configured —
 * resolved via {@link ObjectProvider} so their absence yields attributes-only behaviour.
 */
@AutoConfiguration
@ConditionalOnClass({ObservationRegistry.class, Tracer.class})
@ConditionalOnProperty(prefix = "dapr.spring-ai", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DurableObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DurableTracing daprDurableTracing(
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<Tracer> tracer,
      ObjectProvider<Propagator> propagator) {
    return new MicrometerDurableTracing(
        observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
        tracer.getIfAvailable(),
        propagator.getIfAvailable());
  }
}
