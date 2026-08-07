package io.diagrid.springai.registry.boot;

import io.diagrid.springai.registry.AgentRecordFactory;
import io.diagrid.springai.registry.AgentRegisteringAdvisor;
import io.diagrid.springai.registry.AgentRegistrar;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Binds an agent identity to each {@code ChatClient} bean by its bean name: every ChatClient bean is
 * re-built (via {@link ChatClient#mutate()}) with an {@link AgentRegisteringAdvisor} carrying that
 * name, so the agent registers itself on its first call.
 *
 * <p><b>Limitation:</b> only ChatClients that are Spring beans are seen here. A ChatClient built
 * inline inside a service (not exposed as a bean) is not registered — expose it as a bean to give it
 * an identity. The agent name is the bean name, and a single shared ChatClient bean is one agent.
 *
 * <p>Dependencies are resolved lazily via {@link ObjectProvider} so this post-processor does not
 * force the registrar/factory (and the Dapr client) to initialize before the beans they describe.
 */
public final class ChatClientAgentBeanPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<AgentRegistrar> registrar;
  private final ObjectProvider<AgentRecordFactory> factory;

  /**
   * @param registrar provider for the shared registrar
   * @param factory   provider for the shared record factory
   */
  public ChatClientAgentBeanPostProcessor(
      ObjectProvider<AgentRegistrar> registrar, ObjectProvider<AgentRecordFactory> factory) {
    this.registrar = registrar;
    this.factory = factory;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ChatClient chatClient) {
      AgentRegisteringAdvisor advisor =
          new AgentRegisteringAdvisor(beanName, registrar.getObject(), factory.getObject());
      return chatClient.mutate().defaultAdvisors(advisor).build();
    }
    return bean;
  }
}
