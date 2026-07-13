# dapr-spring-ai-starter

Spring Boot starter that makes any Spring AI 2.0 `ChatClient.call()` durable by running it as a
Dapr Workflow — put it on the classpath, no other code required.

For *how* the durable advisor attaches (and the "must build from the Spring-managed
`ChatClient.Builder`" rule) and the durability model, see the
[project README](../../README.md) — especially the **Wiring** and **Execution identity** sections.
This doc is the short cookbook for defining an agent correctly.

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

### Wait-budget timeout and retry

By default each call runs under a fresh random workflow instance id (dapr-agents parity — no dedup,
no content hashing). A call blocks only for `dapr.spring-ai.completion-timeout`; if that elapses the
workflow keeps running and the call throws `DurableCallTimeoutException` carrying the instance id —
the timeout is a wait budget, not a failure. The id (and workflow name) are echoed on every
successful response — in `ChatClientResponse.context()` and `ChatResponse.getMetadata()` under
`dapr.spring-ai.instance-id` / `dapr.spring-ai.workflow-name`, for correlation.

**To recover a timed-out (or crashed) call, schedule under an id you own and repeat the same call**
with that id: a repeat attaches to the existing instance — running → waits, completed → returns the
recorded answer, failed → surfaces the recorded failure. Loop until it returns:

```java
String myId = "checkout-" + orderId;          // an id you own; guard it like a primary key
String answer = null;
while (answer == null) {
  try {
    answer = chat.prompt().user(message)
        .advisors(a -> a.param(DurableAdvisor.INSTANCE_ID_KEY, myId))
        .call().content();                     // first call creates it; repeats attach to it
  } catch (DurableCallTimeoutException e) {
    log.warn("still running as {} — retrying (attaches, doesn't restart)", e.instanceId());
  }
}
```

Because the successful repeat runs the full advisor chain, chat memory records the answer as usual.
(Best-effort: the memory advisor also re-records the user message on the retry, so a failed-then-
retried turn shows the question twice — harmless.) To re-run a spent id, purge it first via the
injectable `DaprWorkflowClient`: `daprWorkflowClient.purgeWorkflow(myId)`. With a generated id (none
supplied) there's no attach handle — inspect the still-running run via the Diagrid dashboard or
`dapr workflow` CLI using the id from the exception.

> `ChatMemory.CONVERSATION_ID` is unrelated to durability — it is purely Spring AI's chat-memory
> grouping key (see the `dapr-spring-ai-memory` starter). It does not affect the workflow instance id.

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
