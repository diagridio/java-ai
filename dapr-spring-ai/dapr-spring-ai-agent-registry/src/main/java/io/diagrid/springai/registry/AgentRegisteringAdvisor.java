package io.diagrid.springai.registry;

import io.diagrid.springai.registry.model.AgentMetadataSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * Pass-through {@link CallAdvisor} that enriches its agent's registry record with the live system
 * prompt and advertised tools the first time the {@code ChatClient} it is attached to is called,
 * then delegates down the chain unchanged. (A thin record is written eagerly at startup; this
 * supersedes it once a real call is seen.)
 *
 * <p>Runs at high precedence so it observes the call even when a terminal/short-circuiting advisor
 * (e.g. the durability advisor) is also present. Registration is a best-effort side effect: any
 * failure is logged and swallowed so it can never break the user's call, and is retried on the next
 * call until it succeeds.
 */
public final class AgentRegisteringAdvisor implements CallAdvisor {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRegisteringAdvisor.class);

  /**
   * Name <b>prefix</b> of the durability advisor; its presence in the chain marks an agent as durable.
   * Per-agent instances are named {@code DaprDurableAdvisor[<workflowName>]}, so detection is by prefix.
   */
  public static final String DURABLE_ADVISOR_NAME = "DaprDurableAdvisor";

  private final String agentName;
  private final AgentRegistrar registrar;
  private final AgentRecordFactory factory;
  private volatile boolean registered;

  /**
   * @param agentName the agent's name (the ChatClient bean name)
   * @param registrar writes the record to the state store
   * @param factory   builds the record from the live call
   */
  public AgentRegisteringAdvisor(String agentName, AgentRegistrar registrar, AgentRecordFactory factory) {
    this.agentName = agentName;
    this.registrar = registrar;
    this.factory = factory;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    if (!registered) {
      try {
        AgentMetadataSchema schema = factory.build(agentName, request, isDurable(chain));
        if (registrar.register(schema)) {
          registered = true;
        }
      } catch (RuntimeException e) {
        LOG.warn("Agent registration skipped for '{}': {}", agentName, e.toString());
      }
    }
    return chain.nextCall(request);
  }

  // An agent is durable when the durability advisor is present in its call chain (match by name
  // prefix, since per-agent instances are named DaprDurableAdvisor[<workflowName>]).
  private static boolean isDurable(CallAdvisorChain chain) {
    return chain.getCallAdvisors().stream().anyMatch(a -> a.getName().startsWith(DURABLE_ADVISOR_NAME));
  }

  @Override
  public String getName() {
    return "DaprAgentRegisteringAdvisor";
  }

  @Override
  public int getOrder() {
    // Early enough to run before the durability advisor's short-circuit; pass-through either way.
    return Ordered.HIGHEST_PRECEDENCE + 1000;
  }
}
