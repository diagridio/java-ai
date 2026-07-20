package io.diagrid.dapr.springai.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The stored form of a conversation — its messages, as one value in the Dapr state store keyed by
 * conversation id.
 *
 * @param messages the conversation's messages
 */
public record StoredConversation(@JsonProperty("messages") List<StoredMessage> messages) {
}
