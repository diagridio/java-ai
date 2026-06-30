package io.diagrid.springai.durable.boot;

import io.diagrid.springai.durable.client.DurableRunner;
import io.diagrid.springai.durable.conversation.MessageCodec;
import io.diagrid.springai.durable.instance.InstanceIdDerivation;
import io.diagrid.springai.durable.workflow.AgentWorkflow;
import io.diagrid.springai.durable.workflow.ChatOptionsFactory;
import io.diagrid.springai.durable.workflow.LlmInvokeActivity;
import io.diagrid.springai.durable.workflow.ToolInvokeActivity;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.dapr.workflows.runtime.WorkflowRuntime;
import io.dapr.workflows.runtime.WorkflowRuntimeBuilder;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that makes Spring AI {@code ChatClient} calls durable via Dapr Workflows.
 *
 * <p>Activates when a {@link ChatModel} bean is present and {@code dapr.spring-ai.enabled} is not
 * false. Wires the Dapr workflow client, an in-process workflow runtime (the worker, registering the
 * generic agent workflow plus the LLM and tool activities), discovers {@code @Tool} beans, and adds
 * the {@link DurableAdvisor} to every {@code ChatClient} via a {@link ChatClientCustomizer}. No user
 * code changes required.
 */
// Activates when Spring AI + Dapr workflows are on the classpath and not disabled. We do NOT use
// @ConditionalOnBean(ChatModel): it is unreliable for beans created by other autoconfigurations.
// Instead the bean methods inject ChatModel directly, resolved at instantiation once every
// autoconfiguration has contributed its definitions.
@AutoConfiguration
@ConditionalOnClass({ChatModel.class, DaprWorkflowClient.class})
@ConditionalOnProperty(prefix = "dapr.spring-ai", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(DaprSpringAiProperties.class)
public class DaprSpringAiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DaprWorkflowClient daprWorkflowClient() {
    return new DaprWorkflowClient();
  }

  @Bean
  @ConditionalOnMissingBean
  public InstanceIdDerivation instanceIdDerivation(DaprSpringAiProperties properties) {
    return new InstanceIdDerivation(properties.requireConversationId());
  }

  @Bean
  @ConditionalOnMissingBean
  public DurableRunner durableRunner(
      DaprWorkflowClient client, InstanceIdDerivation idDerivation, DaprSpringAiProperties properties) {
    return new DurableRunner(client, idDerivation, properties.completionTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  public ChatOptionsFactory daprChatOptionsFactory(ChatModel chatModel) {
    return new DefaultChatOptionsFactory(chatModel);
  }

  @Bean
  public DiscoveredTools daprDiscoveredTools() {
    return new DiscoveredTools();
  }

  /** Populate the tool registry/specs once every singleton (including user {@code @Tool} beans) exists. */
  @Bean
  public SmartInitializingSingleton daprToolDiscoveryInitializer(
      DiscoveredTools tools, ApplicationContext context) {
    return () -> tools.populate(context);
  }

  /** The in-process workflow worker. Started non-blocking; closed on shutdown. */
  @Bean(destroyMethod = "close")
  public WorkflowRuntime daprWorkflowRuntime(
      ChatModel chatModel,
      ChatOptionsFactory optionsFactory,
      DiscoveredTools tools,
      DaprSpringAiProperties properties)
      throws Exception {
    // Retry options are fixed here at startup and registered with the (single) workflow instance,
    // so they stay constant across replays.
    AgentWorkflow workflow = new AgentWorkflow(properties.retry().toWorkflowTaskOptions());
    WorkflowRuntime runtime =
        new WorkflowRuntimeBuilder()
            .registerWorkflow(workflow)
            .registerActivity(
                AgentWorkflow.LLM_ACTIVITY, new LlmInvokeActivity(chatModel, optionsFactory))
            .registerActivity(AgentWorkflow.TOOL_ACTIVITY, new ToolInvokeActivity(tools.registry()))
            .build();
    runtime.start(false);
    return runtime;
  }

  @Bean
  public DurableAdvisor daprDurableAdvisor(DurableRunner runner, DiscoveredTools tools) {
    // The advisor reads global specs + request-scoped tools per call, and registers request tool
    // callbacks into the shared registry, so it needs the live DiscoveredTools (not a specs snapshot).
    return new DurableAdvisor(runner, tools, new MessageCodec());
  }

  @Bean
  public ChatClientCustomizer daprDurableChatClientCustomizer(DurableAdvisor advisor) {
    return builder -> builder.defaultAdvisors(advisor);
  }
}
