package io.diagrid.springai.registry.boot;

import io.diagrid.springai.registry.model.ToolMetadata;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

/**
 * Discovers the application's global {@code @Tool} beans as {@link ToolMetadata}.
 *
 * <p>The durability starter advertises every {@code @Tool} bean to every durable agent (via its
 * {@code DiscoveredTools}/{@code DurableAdvisor}), but that union happens inside that starter and is
 * invisible here. So the registry scans the beans itself to record the same surface. The scan mirrors
 * {@code DiscoveredTools.populate} — the registry can't depend on the starter — resolving each bean's
 * type first so unrelated {@code @Lazy} beans aren't force-initialized.
 */
final class GlobalToolCatalog {

  private GlobalToolCatalog() {
  }

  static List<ToolMetadata> scan(ApplicationContext context) {
    List<ToolMetadata> tools = new ArrayList<>();
    for (String beanName : context.getBeanDefinitionNames()) {
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
        tools.add(
            new ToolMetadata(
                callback.getToolDefinition().name(),
                callback.getToolDefinition().description(),
                callback.getToolDefinition().inputSchema()));
      }
    }
    return tools;
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
