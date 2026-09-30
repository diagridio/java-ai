package io.diagrid.springai.durable.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** The usage-analytics bean must be switchable off with a Spring property, not only the environment. */
class UsageAnalyticsConditionTest {

  @Test
  void initializerBeanIsGatedByTheAnalyticsEnabledProperty() throws NoSuchMethodException {
    Method bean = DiagridSpringAiAutoConfiguration.class.getMethod("daprUsageAnalyticsInitializer");
    ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
    assertNotNull(condition, "daprUsageAnalyticsInitializer must carry @ConditionalOnProperty");
    assertEquals("diagrid.spring-ai.analytics", condition.prefix());
    assertArrayEquals(new String[] {"enabled"}, condition.name());
    assertEquals("true", condition.havingValue());
    assertTrue(condition.matchIfMissing(), "analytics must stay on when the property is absent");
  }
}
