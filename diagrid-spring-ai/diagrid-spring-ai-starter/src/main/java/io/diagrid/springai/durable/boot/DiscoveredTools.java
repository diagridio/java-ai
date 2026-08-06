package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.workflow.ToolRegistry;
import io.diagrid.springai.durable.workflow.ToolSpec;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

/**
 * The application's {@code @Tool} beans, exposed both as an executable {@link ToolRegistry} (used by
 * the tool activity, keyed by tool name) and as {@link ToolSpec}s (the tool surface advertised to
 * the model in each durable call).
 *
 * <p>Populated once via {@link #populate} after all singletons are instantiated — not during bean
 * creation — so scanning never forces premature initialization. The {@link ToolRegistry} instance is
 * stable, so the worker and advisor can hold a reference before population; no workflow runs until
 * the app starts serving requests, by which point the registry is filled.
 *
 * <p>Tools must be singleton beans with no per-request state — the activity may run on any replica
 * or after a crash. The standard {@code @Tool}-method-on-a-Spring-bean pattern satisfies this.
 */
public final class DiscoveredTools {

  private static final Logger LOG = LoggerFactory.getLogger(DiscoveredTools.class);

  private final ToolRegistry registry = new ToolRegistry();
  private final List<ToolSpec> specs = new CopyOnWriteArrayList<>();

  public ToolRegistry registry() {
    return registry;
  }

  public List<ToolSpec> specs() {
    return List.copyOf(specs);
  }

  /** Scans the context for beans carrying {@code @Tool} methods and fills the registry + specs. */
  public void populate(ApplicationContext context) {
    for (String beanName : context.getBeanDefinitionNames()) {
      // Resolve the bean TYPE without instantiating it, so scanning never forces a @Lazy (or
      // otherwise deferred) bean to initialize just to check for @Tool methods. Only beans that
      // actually declare a @Tool method are instantiated below.
      Class<?> type;
      try {
        type = context.getType(beanName, false);
      } catch (RuntimeException e) {
        continue; // unresolvable/abstract bean definition — skip
      }
      if (type == null || !hasToolMethod(type)) {
        continue;
      }
      Object bean;
      try {
        bean = context.getBean(beanName);
      } catch (RuntimeException e) {
        continue; // scoped/unresolvable at runtime — skip
      }
      for (ToolCallback callback : ToolCallbacks.from(bean)) {
        ToolSpec spec =
            new ToolSpec(
                callback.getToolDefinition().name(),
                callback.getToolDefinition().description(),
                callback.getToolDefinition().inputSchema());
        registry.register(spec, callback::call);
        specs.add(spec);
        LOG.info("Registered durable tool '{}' from bean '{}'", spec.name(), beanName);
      }
    }
  }

  private static boolean hasToolMethod(Class<?> type) {
    for (Method method : type.getMethods()) {
      if (method.isAnnotationPresent(Tool.class)) {
        return true;
      }
    }
    return false;
  }
}
