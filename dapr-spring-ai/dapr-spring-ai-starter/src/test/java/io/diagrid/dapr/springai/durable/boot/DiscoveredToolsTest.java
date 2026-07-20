package io.diagrid.dapr.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Tool discovery resolves each bean's TYPE before deciding whether to instantiate it, so scanning
 * for {@code @Tool} methods never forces an unrelated {@code @Lazy} bean to initialize.
 */
class DiscoveredToolsTest {

  static final class Counter {
    static int instantiations;
  }

  /** A lazy bean with no @Tool methods; constructing it (which tool scanning must NOT do) is observable. */
  static final class ExpensiveNonToolBean {
    ExpensiveNonToolBean() {
      Counter.instantiations++;
    }

    public String doWork() {
      return "work";
    }
  }

  static final class WeatherTools {
    @Tool(description = "Get the current weather for a city")
    public String getWeather(String city) {
      return "sunny";
    }
  }

  @Test
  void doesNotInstantiateLazyNonToolBeansWhileStillDiscoveringTools() {
    Counter.instantiations = 0;
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean("expensive", ExpensiveNonToolBean.class, bd -> bd.setLazyInit(true));
    context.registerBean("weatherTools", WeatherTools.class);
    context.refresh();

    DiscoveredTools tools = new DiscoveredTools();
    tools.populate(context);

    assertEquals(
        0, Counter.instantiations, "a @Lazy non-tool bean must not be instantiated by tool scanning");
    assertTrue(tools.registry().has("getWeather"), "the @Tool bean must still be discovered");
    context.close();
  }
}
