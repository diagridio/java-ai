package io.diagrid.springai.registry.boot;

import io.diagrid.springai.registry.AgentRecordFactory;
import io.diagrid.springai.registry.AgentRegisteringAdvisor;
import io.diagrid.springai.registry.AgentRegistrar;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

/**
 * Registers a thin record (name, app id, model) for every {@code ChatClient} bean once all
 * singletons exist, so agents appear in the registry at startup — before any call. The first call
 * later enriches the record with the live system prompt and advertised tools.
 *
 * <p>Best-effort: if the Dapr sidecar is not yet reachable at startup the thin write fails quietly
 * (logged), and the first-call enrichment becomes the effective registration.
 */
public final class AgentRegistryInitializer implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRegistryInitializer.class);

  private final ApplicationContext context;
  private final AgentRegistrar registrar;
  private final AgentRecordFactory factory;

  /**
   * @param context   context to discover {@code ChatClient} beans from
   * @param registrar writes the records
   * @param factory   builds the thin records
   */
  public AgentRegistryInitializer(
      ApplicationContext context, AgentRegistrar registrar, AgentRecordFactory factory) {
    this.context = context;
    this.registrar = registrar;
    this.factory = factory;
  }

  @Override
  public void afterSingletonsInstantiated() {
    boolean durable = durabilityPresent();
    Map<String, ChatClient> clients = context.getBeansOfType(ChatClient.class);
    LOG.info("Eagerly registering {} ChatClient agent(s) (durable={})", clients.size(), durable);
    for (String beanName : clients.keySet()) {
      registrar.register(factory.buildThin(beanName, durable));
    }
  }

  // App-level durability: the durability advisor is registered as a bean when the layer is active.
  // (The per-call chain gives the precise per-agent answer once an agent is first called.)
  private boolean durabilityPresent() {
    return context.getBeansOfType(CallAdvisor.class).values().stream()
        .anyMatch(a -> a.getName().startsWith(AgentRegisteringAdvisor.DURABLE_ADVISOR_NAME));
  }
}
