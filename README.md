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

## Durability key (demo vs production)

Each call is run as a workflow identified by a deterministic **instance id** —
that id is what lets a reissued call reattach to an in-flight or completed
workflow instead of starting (and double-executing) a new one. It is resolved
two ways:

- **With a conversation id** (`ChatMemory.CONVERSATION_ID` set on the call) →
  the id is `conversationId + turn`. Stable and robust to non-deterministic
  prompt content. **This is the production path.**
- **Without one** → a content hash of the request. Zero-config, so the
  examples and demos "just work" — but it is brittle (any non-deterministic
  prompt content, e.g. reordered RAG chunks or a timestamp, changes the hash
  and defeats dedup). **Not recommended for production.** The library logs a
  one-time `WARN` the first time it falls back to the hash.

For production, set a conversation id on your calls, and optionally set
`dapr.spring-ai.require-conversation-id=true` so a call without one fails fast
instead of silently using the hash.

## Workflow names: per agent, one orchestrator

A single generic orchestrator runs every call, but it is registered under
**multiple names**, all in the Dapr Agents shape (`dapr.spring-ai.….workflow`):
**for each `ChatClient` bean, a per-agent name** `dapr.spring-ai.<bean>.workflow`
(so a bean named `weatherAssistant` runs under
`dapr.spring-ai.weatherAssistant.workflow`), **plus a generic fallback**
`dapr.spring-ai.workflow` for `ChatClient`s built inline (not beans). The
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
`dapr.spring-ai.weatherAssistant.workflow`), the explicit key tooling uses to
correlate the agent with its workflows.

Configure under `dapr.spring-ai.registry`:

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.registry.enabled` | `true` | register agents at all |
| `dapr.spring-ai.registry.statestore` | `agent-registry` | Dapr state store component |
| `dapr.spring-ai.registry.team` | `default` | namespaces the registry keys |
| `dapr.spring-ai.registry.app-id` | `spring.application.name` | Dapr app id on each record |

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

## Roadmap

- [x] `dapr-spring-ai` — durable `ChatClient` over Dapr Workflows
- [ ] Dapr [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/) integration — Spring AI `ChatModel` backed by Dapr's Conversation building block
- [ ] Chat memory backed by a Dapr state store — durable conversation history via Spring AI's `ChatMemory`
- [x] Agent registry backed by a Dapr state store
- [x] Spring Boot auto-configuration / starter
- [ ] Durable streaming (`ChatClient.stream()`) — today only `.call()` is durable
- [ ] Workflow versioning — safely evolve the orchestrator with in-flight instances

## Requirements

- Java 17+
- Maven
- A Dapr sidecar (workflow building block enabled)

## License

TBD.
