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
tool crash-safety, and passing a conversationId.

## Durability key (demo vs production)

Each call runs as a workflow identified by a deterministic **instance id** —
that id is what lets a reissued call reattach to an in-flight or completed
workflow instead of starting (and double-executing) a new one. It is a pure
function of the request, resolved two ways:

- **With a conversation id** (`ChatMemory.CONVERSATION_ID` set on the call) →
  `dsa-c-<conversationId>-<hash8>`: a short content hash of the request,
  namespaced by the conversation so a dashboard can group a conversation's turns
  and hash collisions stay scoped to one conversation. **Recommended for
  production** — set a conversation id and, optionally,
  `dapr.spring-ai.require-conversation-id=true` so a call without one fails fast.
- **Without one** → `dsa-h-<hash>`: the same content hash over the whole request,
  un-namespaced. Zero-config, so demos "just work"; the library logs a one-time
  `WARN` the first time it falls back.

Both paths key on **content**, so both share one caveat: non-deterministic prompt
content (reordered RAG chunks, an injected timestamp) changes the hash and
defeats reattach. For reliable dedup, keep a given turn's seed prompt
deterministic. (An earlier design keyed the conversation path on a turn *count*
to sidestep this, but Spring AI's `MessageWindowChatMemory` evicts old messages,
making any count non-monotonic — two turns could collide on the same count and
return a stale answer — so the key is content-based.)

> **Drift from Dapr Agents.** Dapr Agents schedules each run under a random
> `uuid.uuid4().hex` (unless you pass one) and relies on its external memory store
> for continuity — a reissue starts a fresh workflow, with no dedup. This library
> derives the id from the request instead, so a reissue reattaches and never
> re-runs completed LLM calls or tool side effects. The cost is the caveat above;
> random ids avoid it but give no reattach.

### Configuration

Core durability properties (the `retry`, `registry`, and `memory` sub-namespaces
have their own tables in the sections below):

| Property | Default | Meaning |
|----------|---------|---------|
| `dapr.spring-ai.enabled` | `true` | make `ChatClient` calls durable at all |
| `dapr.spring-ai.require-conversation-id` | `false` | strict mode: fail a call with no conversation id instead of falling back to the content-hash key |
| `dapr.spring-ai.completion-timeout` | `5m` | how long a call blocks on its workflow; if it elapses the workflow keeps running and a reissue reattaches, so no work is lost |
| `dapr.spring-ai.failed-instance-policy` | `fail` | reissue onto a **failed/terminated** run: `fail` surfaces the recorded failure (a deterministically-failing request isn't re-run every reissue); `retry` recreates it under the same id |
| `dapr.spring-ai.max-iterations` | `20` | hard cap on LLM turns per call; the workflow fails if the model still requests tools past it (guards against a runaway tool loop — stock Spring AI's loop is unbounded) |

A reissue onto a **completed** run returns the recorded result (no re-execution),
and the runner **only schedules when no instance exists yet** — a reissue that
lands on an in-flight run just attaches and waits (never re-schedules), which is
what keeps duplicate reissues from double-running an agent.

## Workflow names: per agent, one orchestrator

A single generic orchestrator runs every call, but it is registered under
**multiple names**, all sharing the shape `spring-ai.….workflow`:
**for each `ChatClient` bean, a per-agent name** `spring-ai.<bean>.workflow`
(so a bean named `weatherAssistant` runs under
`spring-ai.weatherAssistant.workflow`), **plus a generic fallback**
`spring-ai.workflow` for `ChatClient`s built inline (not beans). The
`.workflow` suffix and `dapr.<framework>.<agent>` shape are what let tooling like
the Diagrid dashboard correlate an agent to its workflows.

This stays zero-code: the names come from your bean names, discovered at startup
(durabletask requires every workflow name to be registered before it can be
scheduled, which the auto-configuration does from the ChatClient bean
definitions). Dedup/reattach is unchanged — **agents/conversations are still
distinguished by the instance id** (the conversation-keyed id above), not the
workflow name.

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
workflow state, not the tool's implementation. Note that retries (below) don't
rescue this case: a request-scoped tool whose callback is gone after a cold
restart will exhaust its attempts and then fail the workflow.

**Tool names share one namespace.** The execution registry maps a tool *name* to
a single implementation, last registration wins. If two agents register
different tools under the same name, the later one shadows the earlier for
*everyone*, and a workflow can run the wrong implementation. Keep tool names
unique across the application (e.g. prefix them per agent). (This affects only
execution; for what's *advertised* to the model, a request-scoped tool correctly
overrides a same-named global one for that call.)

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
- **The system prompt and tools are filled on first call**, not at startup — an
  agent is present from boot but its prompt/tools appear only after it is used.
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
  memory is user data. Failing is cheap: the durable layer means a reissued turn
  reattaches to the completed workflow (no LLM re-run) and re-saves against the
  now-current etag. The etag guards every turn after the first; a first write has
  no etag yet, so two *simultaneous first turns* on a brand-new conversation id can
  still race (store-dependent).

## Roadmap

- [x] `dapr-spring-ai` — durable `ChatClient` over Dapr Workflows
- [ ] Dapr [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/) integration — Spring AI `ChatModel` backed by Dapr's Conversation building block
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
  the timeout is only a wait budget — the workflow keeps running past it and a re-issued call
  reattaches, so no work is lost. (Java 25 runs fine too, but build the library on 17/21 — some
  static-analysis tooling misbehaves on 25.)
- Maven
- A Dapr sidecar (workflow building block enabled)

## License

TBD.
