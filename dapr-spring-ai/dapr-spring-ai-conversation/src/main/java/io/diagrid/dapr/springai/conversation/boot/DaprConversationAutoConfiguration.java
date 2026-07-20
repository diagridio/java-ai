package io.diagrid.dapr.springai.conversation.boot;

import io.dapr.client.DaprClientBuilder;
import io.dapr.client.DaprPreviewClient;
import io.diagrid.dapr.springai.conversation.DaprConversationChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * Auto-configuration for the Dapr Conversation API {@link ChatModel}.
 *
 * <p>Follows the same selection convention as Spring AI's provider starters (OpenAI, Ollama, …):
 * the model registers unless {@code spring.ai.model.chat} selects a different provider. Set
 * {@code spring.ai.model.chat=dapr} to pick this model explicitly when several chat starters are on
 * the classpath, or {@code spring.ai.model.chat=none} to switch all of them off — with no property
 * set, every present starter registers its model, and Spring reports the ambiguity at the first
 * single-{@code ChatModel} injection point.
 *
 * <p>The Conversation API lives on {@link DaprPreviewClient}; the bean is defined with the same
 * {@code @ConditionalOnMissingBean} convention the sibling modules use for {@code DaprClient}, so an
 * app-supplied client wins. Likewise a user-defined {@link DaprConversationChatModel} bean wins over
 * the auto-configured one.
 */
@AutoConfiguration
@ConditionalOnClass({ChatModel.class, DaprPreviewClient.class})
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL,
    havingValue = DaprConversationAutoConfiguration.MODEL_NAME, matchIfMissing = true)
@EnableConfigurationProperties(DaprConversationProperties.class)
public class DaprConversationAutoConfiguration {

  /** Value selecting this model via {@code spring.ai.model.chat}. */
  public static final String MODEL_NAME = "dapr";

  // @Fallback because the SDK's preview client is a DaprClientImpl, which also implements
  // DaprClient: without it, this bean becomes a second candidate for the sibling modules'
  // (memory/registry) DaprClient injection points and breaks them with an ambiguity error.
  // As a fallback it loses any such contest but is still injected where it is the sole candidate.
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public DaprPreviewClient daprPreviewClient() {
    return new DaprClientBuilder().buildPreviewClient();
  }

  @Bean
  @ConditionalOnMissingBean
  public DaprConversationChatModel daprConversationChatModel(DaprPreviewClient client,
      DaprConversationProperties properties) {
    if (properties.component() == null || properties.component().isBlank()) {
      throw new IllegalStateException(
          "dapr.spring-ai.conversation.component is required: name the Dapr conversation component "
              + "that routes LLM traffic (e.g. a conversation.openai or conversation.echo "
              + "component), or select another chat model via spring.ai.model.chat.");
    }
    return new DaprConversationChatModel(client, properties.component(), properties.contextId(),
        properties.scrubPii(), properties.temperature());
  }
}
