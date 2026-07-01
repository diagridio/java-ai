package io.diagrid.springai.registry.boot;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.diagrid.springai.registry.AgentRecordFactory;
import io.diagrid.springai.registry.AgentRegistrar;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers Spring AI {@code ChatClient} agents in a Dapr state store the
 * first time each is called. Activates when Spring AI's ChatClient and the Dapr client are on the
 * classpath and {@code dapr.spring-ai.registry.enabled} is not false. Requires a {@link ChatModel}
 * bean (the source of the recorded provider/model) and registers ChatClients defined as beans.
 */
@AutoConfiguration
@ConditionalOnClass({ChatClient.class, DaprClient.class})
@ConditionalOnProperty(prefix = "dapr.spring-ai.registry", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(AgentRegistryProperties.class)
public class AgentRegistryAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DaprClient daprClient() {
    return new DaprClientBuilder().build();
  }

  @Bean
  @ConditionalOnMissingBean
  public AgentRegistrar daprAgentRegistrar(DaprClient client, AgentRegistryProperties properties) {
    return new AgentRegistrar(client, properties.statestore(), properties.team());
  }

  @Bean
  @ConditionalOnMissingBean
  public AgentRecordFactory daprAgentRecordFactory(
      ChatModel chatModel,
      AgentRegistryProperties properties,
      @Value("${spring.application.name:}") String applicationName) {
    String client = chatModel.getClass().getSimpleName();
    String appId = resolveAppId(properties.appId(), applicationName);
    return new AgentRecordFactory(appId, client, inferProvider(client), defaultModel(chatModel));
  }

  /** Static so the post-processor does not force early initialization of the config class. */
  @Bean
  public static ChatClientAgentBeanPostProcessor daprChatClientAgentBeanPostProcessor(
      ObjectProvider<AgentRegistrar> registrar, ObjectProvider<AgentRecordFactory> factory) {
    return new ChatClientAgentBeanPostProcessor(registrar, factory);
  }

  @Bean
  public AgentRegistryInitializer daprAgentRegistryInitializer(
      ApplicationContext context, AgentRegistrar registrar, AgentRecordFactory factory) {
    return new AgentRegistryInitializer(context, registrar, factory);
  }

  private static String resolveAppId(String configured, String applicationName) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    if (applicationName != null && !applicationName.isBlank()) {
      return applicationName;
    }
    return "spring-ai-app";
  }

  // OllamaChatModel -> ollama, OpenAiChatModel -> openai.
  private static String inferProvider(String clientClass) {
    String base = clientClass.endsWith("ChatModel")
        ? clientClass.substring(0, clientClass.length() - "ChatModel".length())
        : clientClass;
    return (base.isEmpty() ? clientClass : base).toLowerCase(Locale.ROOT);
  }

  private static String defaultModel(ChatModel chatModel) {
    ChatOptions options = chatModel.getDefaultOptions();
    return options == null ? null : options.getModel();
  }
}
