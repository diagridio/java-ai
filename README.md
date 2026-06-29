# java-ai

Durable AI integrations for Java, built on [Dapr](https://dapr.io).

> **Status:** Early development. APIs and module layout are not yet stable.

## What's here

The first integration is **Spring AI durability**: making Spring AI
`ChatClient` calls durable across JVM restarts by running them as
[Dapr Workflows](https://docs.dapr.io/developing-applications/building-blocks/workflow/).

A model call (and any tool calls it triggers) becomes a workflow whose
progress is checkpointed by the Dapr runtime. If the process crashes
mid-conversation, the workflow resumes from the last completed step instead
of replaying the whole interaction or losing it.

## Roadmap

- [ ] `dapr-spring-ai` — durable `ChatClient` over Dapr Workflows
- [ ] Dapr [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/) integration — Spring AI `ChatModel` backed by Dapr's Conversation building block
- [ ] Chat memory backed by a Dapr state store — durable conversation history via Spring AI's `ChatMemory`
- [ ] Agent registry backed by a Dapr state store
- [ ] Spring Boot auto-configuration / starter

## Requirements

- Java 17+
- Maven
- A Dapr sidecar (workflow building block enabled)

## License

TBD.
