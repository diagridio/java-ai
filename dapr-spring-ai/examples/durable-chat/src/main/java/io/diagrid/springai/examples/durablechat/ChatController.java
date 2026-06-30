package io.diagrid.springai.examples.durablechat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ordinary Spring AI usage — no durability code. Because the dapr-spring-ai starter is on the
 * classpath, every {@code chatClient...call()} below runs as a Dapr Workflow and survives a restart.
 *
 * <p>Pass a {@code conversationId} to use it as the durability key (turns within it are
 * distinguished by message count); omit it and the call falls back to a content-hash key.
 */
@RestController
public class ChatController {

  private final ChatClient chatClient;

  public ChatController(ChatClient.Builder builder) {
    this.chatClient =
        builder
            .defaultSystem(
                "You are a travel assistant. When the user asks to book a flight, call the"
                    + " bookFlight tool exactly once, then reply with the confirmation code."
                    + " Do not call the tool again after it returns.")
            .build();
  }

  @PostMapping("/chat")
  public String chat(
      @RequestParam String message,
      @RequestParam(name = "conversationId", required = false) String conversationId) {
    var spec = chatClient.prompt().user(message);
    if (conversationId != null && !conversationId.isBlank()) {
      spec = spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
    }
    return spec.call().content();
  }
}
