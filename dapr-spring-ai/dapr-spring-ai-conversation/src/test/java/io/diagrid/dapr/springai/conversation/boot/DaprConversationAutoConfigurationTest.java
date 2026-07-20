package io.diagrid.dapr.springai.conversation.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprPreviewClient;
import io.diagrid.dapr.springai.conversation.DaprConversationChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DaprConversationAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DaprConversationAutoConfiguration.class))
      // A real preview client would try to reach a sidecar; every context test supplies its own.
      .withUserConfiguration(PreviewClientConfig.class);

  @Test
  void activatesByDefaultWhenTheComponentPropertyIsSet() {
    runner.withPropertyValues("dapr.spring-ai.conversation.component=echo")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertNotNull(context.getBean(DaprConversationChatModel.class));
        });
  }

  @Test
  void activatesWhenSelectedAsTheChatModel() {
    runner.withPropertyValues("spring.ai.model.chat=dapr",
            "dapr.spring-ai.conversation.component=echo")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertNotNull(context.getBean(DaprConversationChatModel.class));
        });
  }

  @Test
  void backsOffWhenAnotherChatModelIsSelected() {
    runner.withPropertyValues("spring.ai.model.chat=openai",
            "dapr.spring-ai.conversation.component=echo")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertEquals(0, context.getBeansOfType(DaprConversationChatModel.class).size());
        });
  }

  @Test
  void backsOffWhenChatModelsAreDisabledWithNone() {
    runner.withPropertyValues("spring.ai.model.chat=none",
            "dapr.spring-ai.conversation.component=echo")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertEquals(0, context.getBeansOfType(DaprConversationChatModel.class).size());
        });
  }

  @Test
  void userDefinedModelBeanWinsOverTheAutoConfiguredOne() {
    runner.withPropertyValues("dapr.spring-ai.conversation.component=echo")
        .withUserConfiguration(ExistingDaprModelConfig.class)
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertEquals(1, context.getBeansOfType(DaprConversationChatModel.class).size());
          assertTrue(context.containsBean("userDaprModel"));
        });
  }

  // Regression: the SDK's preview client is a DaprClientImpl, which also implements DaprClient.
  // Without @Fallback on the daprPreviewClient bean it becomes a second candidate for the sibling
  // modules' (memory/registry) single-DaprClient injection points and fails their startup.
  @Test
  void previewClientDoesNotShadowSingleDaprClientInjections() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DaprConversationAutoConfiguration.class))
        .withUserConfiguration(DaprClientConsumerConfig.class)
        .withPropertyValues("dapr.spring-ai.conversation.component=echo")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertNotNull(context.getBean(DaprClientConsumer.class).client);
          assertNotNull(context.getBean(DaprConversationChatModel.class));
        });
  }

  @Test
  void missingComponentFailsStartupWithAClearMessage() {
    runner.run(context -> {
      assertFalse(context.getStartupFailure() == null);
      Throwable rootCause = rootCause(context.getStartupFailure());
      assertTrue(rootCause.getMessage().contains("dapr.spring-ai.conversation.component"));
    });
  }

  @Test
  void configuredPropertiesReachTheModel() {
    runner.withPropertyValues("dapr.spring-ai.conversation.component=openai",
            "dapr.spring-ai.conversation.context-id=ctx-42",
            "dapr.spring-ai.conversation.scrub-pii=true",
            "dapr.spring-ai.conversation.temperature=0.3")
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          DaprConversationChatModel model = context.getBean(DaprConversationChatModel.class);
          assertEquals(0.3, model.getOptions().getTemperature());
        });
  }

  private static Throwable rootCause(Throwable t) {
    return t.getCause() == null ? t : rootCause(t.getCause());
  }

  // Mirrors the sibling modules: their autoconfigs define a DaprClient bean and inject it
  // unqualified into their own beans.
  static class DaprClientConsumer {
    final DaprClient client;

    DaprClientConsumer(DaprClient client) {
      this.client = client;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class DaprClientConsumerConfig {
    @Bean
    DaprClient daprClient() {
      return mock(DaprClient.class);
    }

    @Bean
    DaprClientConsumer daprClientConsumer(DaprClient client) {
      return new DaprClientConsumer(client);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PreviewClientConfig {
    @Bean
    DaprPreviewClient daprPreviewClient() {
      return mock(DaprPreviewClient.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ExistingDaprModelConfig {
    @Bean
    DaprConversationChatModel userDaprModel() {
      return new DaprConversationChatModel(mock(DaprPreviewClient.class), "custom");
    }
  }

  // Kept for the record: like the provider starters, this autoconfig does NOT back off just
  // because a foreign ChatModel bean exists — selection is spring.ai.model.chat's job.
  @Configuration(proxyBeanMethods = false)
  static class ExistingChatModelConfig {
    @Bean
    ChatModel providerChatModel() {
      return mock(ChatModel.class);
    }
  }

  @Test
  void coexistsWithAForeignChatModelBeanLikeTheProviderStartersDo() {
    runner.withPropertyValues("dapr.spring-ai.conversation.component=echo")
        .withUserConfiguration(ExistingChatModelConfig.class)
        .run(context -> {
          assertTrue(context.getStartupFailure() == null);
          assertEquals(1, context.getBeansOfType(DaprConversationChatModel.class).size());
          assertEquals(2, context.getBeansOfType(ChatModel.class).size());
        });
  }
}
