package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.tracing.DurableTracing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Gives each {@code ChatClient} bean a per-agent durable advisor named after the bean, so its calls
 * run under a workflow named after the agent (registered at startup) instead of the generic type.
 *
 * <p>Runs at higher precedence than the generic advisor that the {@code ChatClientCustomizer} adds to
 * every client, so it wins on beans; inline-built ChatClients (not beans) are not seen here and keep
 * the generic advisor and workflow name. Dependencies are resolved lazily via {@link ObjectProvider}
 * so this post-processor does not force early initialization of the runner or tool registry.
 */
public final class DurableChatClientBeanPostProcessor implements BeanPostProcessor {

  // Per-agent workflow name spring-ai.{agent}.workflow: it contains ".workflow" (tooling like the
  // Catalyst dashboard correlates agents to workflows by that) and encodes the agent (the ChatClient
  // bean name).
  private static final String WORKFLOW_NAME_PREFIX = "spring-ai.";
  private static final String WORKFLOW_NAME_SUFFIX = ".workflow";

  private final ObjectProvider<DurableRunner> runner;
  private final ObjectProvider<DiscoveredTools> tools;
  private final ObjectProvider<DurableTracing> tracing;

  /**
   * @param runner  provider for the shared durable runner
   * @param tools   provider for the shared discovered-tools
   * @param tracing provider for the tracing SPI (absent ⇒ {@link DurableTracing#NOOP})
   */
  public DurableChatClientBeanPostProcessor(
      ObjectProvider<DurableRunner> runner,
      ObjectProvider<DiscoveredTools> tools,
      ObjectProvider<DurableTracing> tracing) {
    this.runner = runner;
    this.tools = tools;
    this.tracing = tracing;
  }

  /**
   * The per-agent workflow name for a ChatClient bean, e.g. {@code spring-ai.weatherAssistant.workflow}.
   * The auto-configuration registers the orchestrator under this same name.
   *
   * @param beanName the ChatClient bean name
   * @return the workflow name
   */
  public static String workflowName(String beanName) {
    return WORKFLOW_NAME_PREFIX + beanName + WORKFLOW_NAME_SUFFIX;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ChatClient chatClient) {
      DurableAdvisor advisor =
          new DurableAdvisor(
              runner.getObject(),
              tools.getObject(),
              new MessageCodec(),
              workflowName(beanName),
              tracing.getIfAvailable(() -> DurableTracing.NOOP));
      return chatClient.mutate().defaultAdvisors(advisor).build();
    }
    return bean;
  }
}
