# java-ai

[![build](https://github.com/diagridio/java-ai/actions/workflows/build.yml/badge.svg)](https://github.com/diagridio/java-ai/actions/workflows/build.yml)

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

## Getting started

Add the Spring Boot starter — it makes every Spring-managed `ChatClient.call()`
durable with no application-code changes:

```xml
<dependency>
  <groupId>io.diagrid.dapr</groupId>
  <artifactId>dapr-spring-ai-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

The other modules are optional and independent — add whichever you need (same
`io.diagrid.dapr` group and version):

| Artifact | What it adds |
|---|---|
| `dapr-spring-ai-starter` | durable `ChatClient` over Dapr Workflows — the one you usually want |
| `dapr-spring-ai-agent-registry` | auto-publish agents to a Dapr state store for discovery |
| `dapr-spring-ai-memory` | durable chat memory backed by a Dapr state store |
| `dapr-spring-ai-conversation` | a `ChatModel` that calls LLMs through the Dapr Conversation API |

All are on Maven Central and need a Dapr sidecar with the workflow building block
enabled — see [Requirements](#requirements) for JDK and runtime notes.

## Wiring: build from the Spring-managed `ChatClient.Builder`

Durability is applied by a Spring AI `CallAdvisor` that the starter attaches via a
`ChatClientCustomizer`, and Spring AI runs customizers **only on the
auto-configured `ChatClient.Builder` bean**. So *how* you build a `ChatClient`
decides whether it's durable:

- ✅ **Inject `ChatClient.Builder`** (the Spring-managed one) and build from it —
  the durable advisor is already on it:
  ```java
  @Component
  class WeatherAgent {
    private final ChatClient client;
    WeatherAgent(ChatClient.Builder builder) {          // managed → customized
      this.client = builder.defaultSystem("...").build();
    }
  }
  ```
- ❌ **`ChatClient.builder(chatModel)`** builds a *fresh, unmanaged* builder — the
  customizer never runs, so the client is **silently not durable**. Same for any
  client you build straight from an injected `ChatModel`.

**Multiple clients in one component** (e.g. a reflection agent with generate +
critique clients): inject the managed builder and `clone()` it per client — each
clone keeps the advisor:

```java
ReflectionAgent(ChatClient.Builder builder) {
  this.generate = builder.clone().defaultSystem("...generate...").build();
  this.critique = builder.clone().defaultSystem("...critique...").build();
}
```

**Or attach the advisor manually** — the `DurableAdvisor` is an injectable bean, so
if you must use `ChatClient.builder(chatModel)`, add it yourself:

```java
ReflectionAgent(ChatModel model, DurableAdvisor durable) {
  this.generate = ChatClient.builder(model).defaultAdvisors(durable).build();
}
```

> **Per-agent workflow names and agent-registry registration need a `ChatClient`
> _bean_.** The steps above make a client *durable*, but per-agent workflow names
> (`spring-ai.<name>.workflow`) and registry entries come from bean
> post-processors that only see `ChatClient` **beans**. A client built as a field
> inside a component is durable but uses the **generic** workflow name and isn't
> registered. To get per-agent naming + registration, expose each `ChatClient` as
> its own `@Bean` (the bean name becomes the agent name).

**The durable advisor is terminal.** It replaces the model call, so it does *not*
call the rest of the advisor chain — it runs at `LOWEST_PRECEDENCE - 1` (just
before Spring AI's terminal `ChatModelCallAdvisor`) and short-circuits there. Your
own advisors that need to run must be ordered *before* it (a lower order / higher
precedence); an advisor ordered *after* it will never run. The library logs a
one-time WARN naming any stranded advisors it finds. Request-observing advisors
(memory, RAG, logging) already sit earlier by default and are unaffected.

See **[`dapr-spring-ai-starter/README.md`](dapr-spring-ai/dapr-spring-ai-starter/README.md)** for a
short cookbook on defining agents — the two shapes (`@Component` vs `@Bean`), the hybrid pattern,
tool crash-safety, and the wait-budget timeout behaviour.

## Execution identity

Every `ChatClient.call()` on the durable path schedules a **new** workflow
instance under a fresh `UUID.randomUUID()`. There is **no** dedup, no content
hashing, and no reattach-by-content: a retried or duplicated call is a new
execution.

**What durability still guarantees**

- **Crash resume.** An in-flight workflow resumes from its history after a worker
  restart; completed activities (finished LLM turns, completed tool calls) are
  never re-executed. This is the core guarantee and is unchanged.

**What it deliberately does not do**

- **Reissue dedup.** If a client retries a call (a double-clicked submit, a
  network retry), it starts a *second* workflow — both run. Idempotency of
  retried side effects is the **tool author's** concern, and the library adds
  **no** dedup of its own: a tool receives only its arguments, so make it
  idempotent on a **business key in those arguments** (e.g. a booking reference,
  or a caller-supplied idempotency token).

**On a wait-budget timeout.** A call blocks only for
`dapr.spring-ai.completion-timeout`. If that elapses the workflow keeps running
and the call throws a `DurableCallTimeoutException` carrying the instance id — the
timeout is a *wait budget*, not a failure. To collect the result, schedule under
an id **you own** and **repeat the same call** with that id: it re-attaches to the
running instance and returns once ready (see *Choosing your own instance id*
below). With a generated id there is no attach handle, so the run is recovered
out-of-band — inspected (and its output read) via the **Diagrid dashboard** or the
`dapr workflow` CLI using that id. The instance id and workflow name are also
echoed on every **successful** response, in `ChatClientResponse.context()` and
`ChatResponse.getMetadata()` under the keys `dapr.spring-ai.instance-id` /
`dapr.spring-ai.workflow-name`, for correlation:

```java
String answer = null;
while (answer == null) {
  try {
    answer = chatClient.prompt().user(msg)
        .advisors(a -> a.param(DurableAdvisor.INSTANCE_ID_KEY, myId))  // an id you own
        .call().content();
  } catch (DurableCallTimeoutException e) {
    log.warn("still running as {} — re-attaching", e.instanceId());    // loop: repeat with myId
  }
}
```

**Choosing your own instance id → retry / recovery (optional).** Omit the id and
each call runs under a fresh random UUID (no dedup). Supply an
id you own and it becomes an **attach handle**: a repeated call with the same id
attaches to the existing instance instead of colliding with it. Set it as an
advisor param; it is echoed back on the response under the same key:

```java
chatClient.prompt().user(msg)
    .advisors(a -> a.param(DurableAdvisor.INSTANCE_ID_KEY, myId))
    .call().content();
```

What a repeated call with the same id does — **same key, same run** (database
semantics):

| The instance is… | The repeated call… |
|------------------|--------------------|
| **running** | waits for it and returns its result |
| **completed** | returns the recorded answer — no re-run |
| **failed / terminated** | surfaces the recorded failure — no re-run (purge the id or use a new one to re-attempt) |
| **absent** (never created, or purged / retention-expired) | runs fresh |

This is how you recover a call that timed out or whose JVM restarted: keep the id,
repeat the same `ChatClient` call, loop until it returns. Because the repeat is a
normal successful call, the **whole advisor chain applies** — chat memory records
the answer, etc. (Best-effort caveat: the memory advisor's request half also runs
on the retry, so the user message appears twice around a failure — question,
question, answer. Harmless to the model; app-level idempotency is the app's
concern.)

Supplied ids are **bearer handles**: anyone who presents the id attaches to the run
(and reads its result). A caller-supplied id may be guessable, unlike a random
UUID — so an app that exposes a retry endpoint should guard the id like a primary
key. To re-run a spent id, purge it first via the injectable `DaprWorkflowClient`:

```java
daprWorkflowClient.purgeWorkflow(myId);  // then a fresh call with myId runs anew
```

> **On the contract.** The default (generated id) is a fresh id per run with no
> reissue dedup. The caller-supplied-id attach is a small, deliberate superset for
> recovery — and an interim one: these semantics are expected to move into the Dapr
> runtime, so the library adds **no** new contract for them (no metadata keys, flags,
> or policies — just a repeated client call).

### Configuration

Core durability properties (the `retry`, `registry`, and `memory` sub-namespaces
have their own tables in the sections below):

| Property | Default | Meaning |
|----------|---------|---------|
| `dapr.spring-ai.enabled` | `true` | make `ChatClient` calls durable at all |
| `dapr.spring-ai.completion-timeout` | `5m` | how long a call blocks on its workflow; if it elapses the call throws `DurableCallTimeoutException` while the workflow keeps running — inspect/recover it via Dapr tooling using the instance id (see above) |
| `dapr.spring-ai.max-iterations` | `20` | hard cap on LLM turns per call; the workflow fails if the model still requests tools past it (guards against a runaway tool loop — stock Spring AI's loop is unbounded) |

By default each call runs under its own fresh instance id, so there is nothing to
configure about dedup — there is none. A retry under a
generated id is a new execution; make tool side effects idempotent on a business
key (see the *Reissue dedup* note above). A retry under an id **you** supply attaches to the
existing run instead — the recovery contract in *Choosing your own instance id*
above.

## Workflow names: per agent, one orchestrator

A single generic orchestrator runs every call, but it is registered under
**multiple names**, all sharing the shape `spring-ai.….workflow`:
**for each `ChatClient` bean, a per-agent name** `spring-ai.<bean>.workflow`
(so a bean named `weatherAssistant` runs under
`spring-ai.weatherAssistant.workflow`), **plus a generic fallback**
`spring-ai.workflow` for `ChatClient`s built inline (not beans). The
`.workflow` suffix is what lets tooling like the Diagrid dashboard correlate an
agent to its workflows.

This stays zero-code: the names come from your bean names, discovered at startup
(durabletask requires every workflow name to be registered before it can be
scheduled, which the auto-configuration does from the ChatClient bean
definitions). The workflow name is a readable grouping label only — **each call
still runs under its own fresh random instance id** (no dedup by id or content),
so the name never affects execution identity.

## Tools and crash recovery

Tools are dispatched as workflow activities, so a completed tool call is never
re-executed on replay. How a tool is registered determines whether an
**in-flight** tool call survives a cold restart:

- **`@Tool` methods on Spring beans** — discovered at startup, so they are
  re-registered every time the app boots. A workflow interrupted mid-tool
  resumes and runs the tool normally after a restart. **Use these for tools
  whose in-flight calls must be crash-recoverable.**
- **Request-scoped tools** attached per-`ChatClient` via
  `.defaultTools(new MyTools())` / `.tools(...)` — advertised per-agent and
  executed correctly on the live path, but the callback is registered in memory at
  call time, not at startup. (Per-agent is the *advertised* surface only —
  execution resolves by bare name in a process-wide registry; see below.) If the
  JVM crashes *after* the model requests such a tool but *before* that tool
  activity completes, the resumed workflow cannot find the callback and that
  instance fails; the registry repopulates on the next call to the agent, so
  new calls keep working. For full mid-workflow recovery, back the tool with a
  `@Tool` bean.

This is a property of executable code not being serializable — Dapr persists
workflow state, not the tool's implementation. Note that retries (below) don't
rescue this case: a request-scoped tool whose callback is gone after a cold
restart will exhaust its attempts and then fail the workflow.

**Tool names share one namespace.** Execution resolves a tool by *bare name* in a
process-wide registry, last-write-wins — so tools must be **stateless** and
**app-uniquely named**. That last-write-wins re-registration is a feature, not a
bug: a request-scoped tool works across replicas precisely because every replica
that serves the agent re-registers the same stateless callback, so wherever Dapr
dispatches the activity a correct copy is present. The flip side is that if two
*different* tools are registered under one name, the later silently shadows the
earlier for everyone — so the registry now logs a **one-time WARN per name** when a
name is registered with a different definition (description/schema), pointing at
the fix (unique names, e.g. a per-agent prefix). Behavior is unchanged
(last-write-wins); it's just no longer silent. What can't be detected — and so
stays unsupported — is a tool that captures **per-request state** in its closure:
two such registrations look identical to a safe stateless one, and under
concurrency a workflow may run the wrong closure. (For what's *advertised* to the
model, a request-scoped tool correctly overrides a same-named global one for that
call.)

## Observability

A caller's trace shows the durable call, and the LLM/tool activity spans nest
under the **originating request's** trace — even though the activities execute on
worker threads (possibly another replica). The trace context is propagated
through the workflow input and restored activity-side, done here with Micrometer.
Three layers join up:

1. **Caller side** — the advisor wraps the blocking schedule+wait in a Micrometer
   `Observation` named `dapr.springai.durable.call` (tags: `workflow_name`,
   `outcome` = `completed | timeout | failed`, and the `instance_id`), and it
   echoes the instance id + workflow name onto the response
   (`ChatClientResponse.context()` and `ChatResponse.getMetadata()`, keys
   `dapr.spring-ai.instance-id` / `dapr.spring-ai.workflow-name`).
2. **Activity side** — the W3C trace context captured inside that observation is
   carried in the workflow input and restored around each activity, which runs
   under a child span (`dapr.springai.llm.invoke` / `dapr.springai.tool.invoke`).
   With the context restored, **Spring AI's own ChatModel `gen_ai` spans/metrics
   parent correctly for free** — that's the main payoff; we don't duplicate what
   Spring AI already records about the model call. (Spans are created only on the
   caller thread and inside activities — never in the orchestrator, whose replays
   would otherwise re-emit them.)
3. **Sidecar workflow tracing** — when an `ObservationRegistry` is present, the
   starter auto-configures Dapr's observed workflow client
   (`ObservationDaprWorkflowClient`, from `dapr-spring-boot-observation`), which
   propagates the caller's trace context to the sidecar at schedule time. The
   sidecar's `durabletask` workflow spans then join the caller's trace rather than
   forming a separate one (requires dapr-sdk-workflows ≥ 1.18 and a sidecar that
   honors the propagated context; otherwise they still correlate by instance id).
4. **Log correlation** — each activity puts the instance id (and, for a tool, the
   tool name) into SLF4J **MDC** for the duration of the call, so logs line up by
   run regardless of any tracing backend.

**No tracing backend configured ⇒ everything no-ops** — zero overhead beyond a
null check (the core defines a tiny `DurableTracing` SPI with a no-op default;
the Micrometer implementation ships in the starter as an *optional* dependency,
activated only when a tracing bridge is present; the observed workflow client
records nothing until a tracing handler is on the `ObservationRegistry`). **MDC
correlation works regardless.** There are no configuration properties: presence
of the `ObservationRegistry` / `Tracer` beans is the switch.

## Retries

The model call and each tool call run as workflow activities, and a transient
failure (a provider rate limit, a network blip) is retried by the Dapr runtime
instead of failing the whole workflow. Retries are **on by default** with
exponential backoff; configure or disable them under `dapr.spring-ai.retry`:

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.retry.enabled` | `true` | retry activities at all |
| `dapr.spring-ai.retry.max-attempts` | `3` | total attempts, including the first |
| `dapr.spring-ai.retry.first-interval` | `1s` | delay before the first retry |
| `dapr.spring-ai.retry.backoff-coefficient` | `2.0` | interval multiplier per attempt |
| `dapr.spring-ai.retry.max-interval` | `30s` | cap on the growing retry interval |

The policy applies equally to the LLM and tool activities and is fixed at
startup, so it stays constant across workflow replays. Retries are bounded by
`max-attempts`, so a genuinely failing call still fails — just after a few tries.

## Model options

Across the durable boundary, chat options are reconstructed from the model's
default options plus the **portable** `ChatOptions` fields captured from the
call: `model`, `temperature`, `maxTokens`, `topP`, `topK`, `stopSequences`,
`frequencyPenalty`, `presencePenalty`. Provider-specific options (e.g. Ollama's
`numCtx`) are only preserved when set as **model defaults** (e.g. in
`application.yml`), not when passed per call — so set provider-specific tuning as
a default rather than on an individual request.

## Agent registry

The `dapr-spring-ai-agent-registry` module (separate dependency, independent of
durability) publishes each agent to a Dapr state store so tooling like the
Diagrid dashboard and other Dapr Agents can discover it. Add the dependency and
it works with no code changes.

Each `ChatClient` **bean** is registered in two steps: a **thin record**
(name, app id, model) is written **at startup** so the agent shows up
immediately, and on its **first call** the record is **enriched** with the live
system prompt and the tools actually advertised. Capturing the prompt/tools from
a real call (rather than guessing at startup) keeps the record accurate without
reflecting into Spring AI internals. The record uses the canonical `dapr-agents`
format — a per-agent key `agents:{team}:{name}` plus a team index
`agents:{team}:_index`. The `type` is `DurableAgent` when the agent runs under
the durability layer (the durable ChatClient advisor is on its chain), otherwise
`Agent` — so a Dapr-backed agent shows up as such in the registry. A durable
agent's record also carries `agent.metadata.workflow_name` (e.g.
`spring-ai.weatherAssistant.workflow`), the explicit key tooling uses to
correlate the agent with its workflows.

Configure under `dapr.spring-ai.registry`:

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.registry.enabled` | `true` | register agents at all |
| `dapr.spring-ai.registry.statestore` | `agent-registry` | Dapr state store component |
| `dapr.spring-ai.registry.team` | `default` | namespaces the registry keys |
| `dapr.spring-ai.registry.app-id` | `spring.application.name`, else `spring-ai-app` | Dapr app id recorded on each agent — **set to your Dapr app id** (see note) |

> **`app-id` must be your actual Dapr app id** (the sidecar's `--app-id`), because tooling
> correlates an agent to its app and workflows by it. There is no reliable way to read the Dapr app
> id from inside the app, so it defaults to `spring.application.name` purely as a convenience for
> apps that name the two the same — if yours differ, set `dapr.spring-ai.registry.app-id` explicitly.

> **The registry state store must use `keyPrefix: none`.** Otherwise Dapr's default
> (`keyPrefix: appid`) prepends `<appId>||` to every key, siloing the registry per app so other
> Dapr Agents can't discover it under the shared `agents:{team}:*` namespace. Catalyst's built-in
> `agent-registry` component (the default) is already configured this way; on self-hosted Dapr,
> create a dedicated component for it — do **not** reuse your durability/workflow store (that one is
> app-scoped with `actorStateStore: true`):
>
> ```yaml
> apiVersion: dapr.io/v1alpha1
> kind: Component
> metadata:
>   name: agent-registry
> spec:
>   type: state.redis
>   version: v1
>   metadata:
>     - name: redisHost
>       value: localhost:6379
>     - name: keyPrefix
>       value: none
> ```

### Identity and limitations

An agent's name is the **`ChatClient` bean name**. This is deliberate (no
annotations, no stack inspection), and it has limits worth knowing:

- **Only ChatClients that are Spring beans are registered.** A `ChatClient`
  built inline inside a service (not exposed as a bean) is invisible to the
  registry — expose it as a `@Bean` to give it an identity.
- **The name is the bean name**, so name your beans meaningfully
  (`weatherAssistant`, `itineraryFormatter`).
- **A single shared `ChatClient` bean is one agent**, even if used for several
  logical roles.
- **The system prompt and request-scoped tools are filled on first call**, not at
  startup — an agent is present from boot but its prompt and per-agent
  (`.defaultTools(...)`) tools appear only after it is used. A durable agent's
  global `@Tool` beans, which it advertises to every call, are listed from startup
  (they're known then); a request-scoped tool wins over a same-named global.
- **Only `.call()` is covered**, not `.stream()`.
- A registry write never breaks a call: failures are logged and swallowed.

## Chat memory

The `dapr-spring-ai-memory` module (separate dependency) backs Spring AI's chat
memory with a Dapr state store, so conversation history survives restarts and is
shared across replicas — durable conversations to go with durable execution.

It provides a `ChatMemoryRepository` (registered *before* Spring AI's default)
that persists each conversation's messages to a Dapr state store keyed by
conversation id; Spring AI's `MessageWindowChatMemory` uses it transparently.
Configure under `dapr.spring-ai.memory`:

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.memory.enabled` | `true` | back chat memory with Dapr |
| `dapr.spring-ai.memory.statestore` | `agent-memory` | Dapr state store component (Catalyst provides this by default) |
| `dapr.spring-ai.memory.agent-name` | `default` | key namespace (see below) |

Notes:

- You still opt into chat memory the usual Spring AI way (a memory advisor on
  your `ChatClient`); this module only makes its storage durable — no code change
  beyond that.
- Keys use the Dapr Agents format **`{agent-name}:_memory_{conversationId}`**
  (agent-name spaces → dashes, whole key lowercased), so records interoperate with
  Dapr Agents' memory layout. Set `agent-name` to a specific agent if you want to
  match it; our `ChatMemoryRepository` is shared across agents, so it's an
  app-level namespace (default `default`).
- Only conversational turns (user/assistant/system) are persisted, as
  `{type, text}`; tool messages are not.
- A user-supplied `ChatMemoryRepository` bean takes precedence.
- **One writer per conversation id.** `MessageWindowChatMemory` does
  read-modify-write, so two concurrent turns on the same conversation id would
  otherwise silently drop one. The save uses optimistic concurrency (etag): a
  conflicting write is **rejected** with a `ConcurrentConversationModificationException`
  rather than merged — merging two windows generated without seeing each other
  would fabricate a false history, so a conflict is treated as a usage error
  (double-submit, missing sticky routing, two agents sharing an id). This is
  deliberately fail-loud, unlike the registry's best-effort log-and-swallow —
  memory is user data. The caller handles a rejected save by retrying the turn
  (a fresh durable execution — there is no reissue dedup), which re-reads the
  now-current etag. The etag guards every turn after the first; a first write has
  no etag yet, so two *simultaneous first turns* on a brand-new conversation id can
  still race (store-dependent).

## Conversation API

The `dapr-spring-ai-conversation` module (separate dependency, independent of
durability) provides a Spring AI `ChatModel` whose LLM calls go through the
Dapr sidecar's [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/)
instead of a provider SDK — swap providers by component configuration, keep
provider SDKs and API keys out of the app, and get sidecar features like PII
scrubbing for free. Tool calling is supported (definitions advertised, tool
calls returned — never executed in-model, so it composes with the durable
path); the API is text-only and non-streaming. Configure under
`dapr.spring-ai.conversation`:

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.conversation.component` | — (**required**) | Dapr conversation component routing LLM traffic |
| `dapr.spring-ai.conversation.context-id` | — | conversation session id handed to the sidecar |
| `dapr.spring-ai.conversation.scrub-pii` | `false` | sidecar obfuscates PII in inputs and outputs |
| `dapr.spring-ai.conversation.temperature` | — | default sampling temperature |

Model selection follows Spring AI's provider-starter convention: the model
registers unless `spring.ai.model.chat` selects another provider (set it to
`dapr` to pick this one explicitly, `none` to disable all chat models).
Capabilities, caveats, and component YAML examples:
[module README](dapr-spring-ai/dapr-spring-ai-conversation/README.md).

## Roadmap

- [x] `dapr-spring-ai` — durable `ChatClient` over Dapr Workflows
- [x] Dapr [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/) integration — Spring AI `ChatModel` backed by Dapr's Conversation building block
- [x] Chat memory backed by a Dapr state store — durable conversation history via Spring AI's `ChatMemory`
- [x] Agent registry backed by a Dapr state store
- [x] Spring Boot auto-configuration / starter
- [ ] Durable streaming (`ChatClient.stream()`) — today only `.call()` is durable
- [ ] Workflow versioning — safely evolve the orchestrator with in-flight instances

## Requirements

- **Java 17+** — the floor set by Spring AI 2.0 (via Spring Framework 7 / Spring Boot 4). The
  library is compiled for Java 17, so it works on any Spring Boot 4 app.
- **Java 21 recommended for the app runtime.** A durable `ChatClient.call()` blocks the caller until
  its workflow completes (the call is synchronous by contract). On Java 21 with
  `spring.threads.virtual.enabled=true`, that wait parks a *virtual* thread — nearly free — so the
  blocking model scales. Pair it with a generous `dapr.spring-ai.completion-timeout` for slow calls;
  the timeout is only a wait budget — the workflow keeps running past it, and the call throws
  `DurableCallTimeoutException` carrying the instance id so the result can be collected later by id,
  so no work is lost. (Java 25 runs fine too, but build the library on 17/21 — some
  static-analysis tooling misbehaves on 25.)
- Maven
- A Dapr sidecar (workflow building block enabled)

## License

[Business Source License 1.1](LICENSE.md) (Diagrid-modified — same terms as the other
Diagrid AI SDKs): free for non-production use and for production use by organizations
under the size thresholds in the Additional Use Grant; converts to Apache License 2.0
on the Change Date. See [LICENSE.md](LICENSE.md) for the exact terms.

## Trademarks

Spring is a trademark of Broadcom Inc. and/or its subsidiaries.
