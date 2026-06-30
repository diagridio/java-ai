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

## Tools and crash recovery

Tools are dispatched as workflow activities, so a completed tool call is never
re-executed on replay. How a tool is registered determines whether an
**in-flight** tool call survives a cold restart:

- **`@Tool` methods on Spring beans** — discovered at startup, so they are
  re-registered every time the app boots. A workflow interrupted mid-tool
  resumes and runs the tool normally after a restart. **Use these for tools
  whose in-flight calls must be crash-recoverable.**
- **Request-scoped tools** attached per-`ChatClient` via
  `.defaultTools(new MyTools())` / `.tools(...)` — these are advertised and
  executed correctly on the live path (and scoped to that agent only), but the
  callback is registered in memory at call time, not at startup. If the JVM
  crashes *after* the model requests such a tool but *before* that tool
  activity completes, the resumed workflow cannot find the callback and that
  instance fails; the registry repopulates on the next call to the agent, so
  new calls keep working. For full mid-workflow recovery, back the tool with a
  `@Tool` bean.

This is a property of executable code not being serializable — Dapr persists
workflow state, not the tool's implementation. There is no retry/backoff on the
tool activity today (a failed tool fails the workflow rather than waiting for
re-registration).

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
