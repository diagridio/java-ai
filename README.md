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

## Workflow type: one for all agents

Every `ChatClient` call runs as the **same** workflow type
(`io.diagrid.springai.durable.workflow.AgentWorkflow`), registered once at
startup. This differs from Dapr Agents, which registers a per-agent workflow
(`dapr.<framework>.<agent>.workflow`) because it has explicit, named agent
objects. Here durability is transparent `ChatClient` interception with no
agent-identity concept, so a single generic workflow serves all of them, and
**agents/conversations are distinguished by the instance id** (the
conversation-keyed id above), not by the workflow name. Per-agent workflow
names would require an opt-in agent name registered at startup (durabletask
needs a workflow name registered before it can be scheduled) and would erode
the zero-code drop-in — a deliberate trade-off, not an oversight.

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

## Roadmap

- [x] `dapr-spring-ai` — durable `ChatClient` over Dapr Workflows
- [ ] Dapr [Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/) integration — Spring AI `ChatModel` backed by Dapr's Conversation building block
- [ ] Chat memory backed by a Dapr state store — durable conversation history via Spring AI's `ChatMemory`
- [ ] Agent registry backed by a Dapr state store
- [x] Spring Boot auto-configuration / starter

## Requirements

- Java 17+
- Maven
- A Dapr sidecar (workflow building block enabled)

## License

TBD.
