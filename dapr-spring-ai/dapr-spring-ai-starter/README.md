# dapr-spring-ai-starter

Spring Boot starter that makes any Spring AI 2.0 `ChatClient.call()` durable by running it as a
Dapr Workflow — put it on the classpath, no other code required.

For *how* the durable advisor attaches (and the "must build from the Spring-managed
`ChatClient.Builder`" rule) and the durability model, see the
[project README](../../README.md) — especially the **Wiring** and **Durability key** sections. This
doc is the short cookbook for defining an agent correctly.

## Defining a durable agent

An "agent" is a `ChatClient`. How you declare it decides (a) whether it's durable and (b) its
workflow name.

**Always build from the injected `ChatClient.Builder`.** `ChatClient.builder(chatModel)` is a static
factory that bypasses Spring AI's customizers, so that client is **not durable** (silently).

### Two shapes

**`@Component` wrapping a client** — durable, runs under the generic workflow name:

```java
@Component
class BookingAgent {
  private final ChatClient chat;
  BookingAgent(ChatClient.Builder builder, BookingTools tools) {   // inject the Builder bean
    this.chat = builder.defaultSystem("You book trips.").defaultTools(tools).build();
  }
}
```

**`@Bean ChatClient`** — durable *and* named per agent, `spring-ai.<beanName>.workflow` (the
`.workflow` convention the Catalyst dashboard correlates by):

```java
@Bean
ChatClient bookingAgent(ChatClient.Builder builder, BookingTools tools) {
  return builder.defaultSystem("You book trips.").defaultTools(tools).build();
}
```

**Hybrid** — idiomatic service *and* per-agent naming: declare the `@Bean ChatClient`, then inject
it into a thin service:

```java
@Service
class BookingService {
  private final ChatClient agent;
  BookingService(@Qualifier("bookingAgent") ChatClient agent) { this.agent = agent; }
}
```

### Tools: prefer `@Tool` Spring beans

`@Tool` methods on beans are discovered at startup and re-registered on every boot, so an in-flight
tool call survives a cold worker restart. Tools attached per-client with `.defaultTools(new Foo())`
work on the live path but are **not** re-registered after a crash — a workflow interrupted mid-tool
can't recover that step.

Nuance: a `@Tool` bean is global (offered to every agent); per-agent scoping is done by attaching
tools to a client via `.defaultTools(...)`. The advertised surface is the global `@Tool` beans plus
the client's own tools (client tools win on name collision). So "agent-scoped" and "crash-safe"
partly trade off today.

### Pass a conversationId

Set `ChatMemory.CONVERSATION_ID` as an advisor param so the workflow instance is keyed by the
conversation:

```java
chat.prompt().user(message)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "trip-42"))
    .call().content();
```

The instance id is then `dsa-c-trip-42-<hash8>` — the conversation id namespaces a short content hash
of the request, so an identical reissue of a turn reattaches. Without a conversationId it falls back
to `dsa-h-<sha256>` (the same hash, un-namespaced). Both key on content; see the root README's
**Durability key** for the determinism caveat they share.

### Registering an inline (non-bean) agent

Auto-registration — like the durable advisor — only sees `ChatClient` **beans**. A client built with
the static `ChatClient.builder(chatModel)`, or held as a field inside a `@Component`, is invisible to
the registry's bean post-processor. Register it exactly the way you'd attach the durable advisor
manually: inject the registry's two beans (`AgentRegistrar`, `AgentRecordFactory`) and add an
`AgentRegisteringAdvisor` carrying the name you want:

```java
@Component
class BookingAgent {
  private final ChatClient chat;

  BookingAgent(ChatModel model, AgentRegistrar registrar, AgentRecordFactory factory) {
    this.chat = ChatClient.builder(model)   // static build → not seen by the registry BPP
        .defaultAdvisors(new AgentRegisteringAdvisor("bookingAgent", registrar, factory))
        .build();
  }
}
```

On first call the agent registers itself, enriched with its live system prompt and advertised tools,
and typed `DurableAgent` when the durability advisor is in its chain (plain `Agent` otherwise).

The manually attached advisor registers on **first call**, not at startup — the eager thin record is
written only for `ChatClient` beans. To make the agent appear before its first call, register a thin
record yourself at startup (pass `durable` explicitly, since there's no call chain to infer it from):

```java
registrar.register(factory.buildThin("bookingAgent", true));
```

Requires the `dapr-spring-ai-agent-registry` module on the classpath.

## Gotchas

- **`ChatClient.builder(chatModel)` → not durable.** Inject `ChatClient.Builder`, or add the
  `DurableAdvisor` bean manually (`.defaultAdvisors(durableAdvisor)`).
- **Non-bean tools aren't crash-recoverable.** Back tools with `@Tool` beans where mid-call recovery
  matters.
- **Per-agent workflow names + registry need a `ChatClient` bean.** A client built as a field is
  durable but uses the generic workflow name and isn't auto-registered — attach
  `AgentRegisteringAdvisor` manually (see **Registering an inline (non-bean) agent** above).
- **The durable advisor is terminal.** It short-circuits the chain (runs just before the terminal
  `ChatModelCallAdvisor`), so any advisor ordered *after* it never runs — order yours *before* it.
  The library WARNs once about stranded advisors. See the root README's **Wiring** section.
