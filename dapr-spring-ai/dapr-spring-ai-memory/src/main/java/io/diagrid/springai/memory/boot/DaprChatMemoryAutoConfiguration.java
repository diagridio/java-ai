package io.diagrid.springai.memory.boot;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.diagrid.springai.memory.DaprStateChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that backs Spring AI chat memory with a Dapr state store.
 *
 * <p>Registers a {@link ChatMemoryRepository} <em>before</em> Spring AI's chat-memory
 * auto-configuration, so the default {@code MessageWindowChatMemory} persists conversation history
 * to Dapr instead of the in-process {@code InMemoryChatMemoryRepository}. Activates when Spring AI's
 * chat memory and the Dapr client are on the classpath and {@code dapr.spring-ai.memory.enabled} is
 * not false. No user code changes beyond using Spring AI chat memory as usual; a user-supplied
 * {@link ChatMemoryRepository} bean still wins.
 */
@AutoConfiguration(
    beforeName = "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration")
@ConditionalOnClass({ChatMemoryRepository.class, DaprClient.class})
@ConditionalOnProperty(prefix = "dapr.spring-ai.memory", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(DaprChatMemoryProperties.class)
public class DaprChatMemoryAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DaprClient daprClient() {
    return new DaprClientBuilder().build();
  }

  @Bean
  @ConditionalOnMissingBean
  public ChatMemoryRepository chatMemoryRepository(DaprClient client, DaprChatMemoryProperties properties) {
    return new DaprStateChatMemoryRepository(client, properties.statestore(), properties.agentName());
  }
}
