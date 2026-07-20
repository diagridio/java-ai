# Travel Planner — Spring AI 2.0 (optional Dapr)

> **Pure Spring AI 2.0 by default; Dapr is opt-in via Spring profiles.** With no profile active the
> app is plain Spring AI — no Dapr, no sidecar. Activating the `dapr`, `memory`, and/or `registry`
> profiles layers in the `io.diagrid.dapr:dapr-spring-ai-*` starters with **no application-code changes**
> (the integration is pure auto-config + `ChatClient.Builder` injection): `dapr` runs each agent's
> LLM+tool loop as a **durable Dapr Workflow**, `memory` persists chat memory in a Dapr state store,
> and `registry` publishes the agents for discovery. See [Running](#running).

A fuller counterpart to the minimal [`durable-chat`](../durable-chat) example: a multi-agent travel
planner on **[Spring AI 2.0](https://docs.spring.io/spring-ai/reference/)** that runs as plain Spring
AI out of the box, and layers in the `dapr-spring-ai` starters — durability, memory, and registry —
via Spring profiles.

Each agent is a `ChatClient` configured with a role-specific system prompt and a set of `@Tool`
methods; Spring AI's `ToolCallingAdvisor` runs the ReAct tool loop automatically. Multi-agent
orchestration is composed at the application layer, mirroring Spring AI's documented
[agentic patterns](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
(Chain → sequential, Parallelization → parallel, Routing → conditional, plus a loop).

## How this maps to the framework

| Concern | This module |
|---|---|
| Agent | `@Component` building a `ChatClient` from the injected `ChatClient.Builder` with `.defaultSystem(<instructions>).defaultTools(<tools>)` (injecting the builder lets the Dapr `DurableAdvisor` attach under the `dapr` profile) |
| Tools | `@Tool` methods; JSON schema auto-generated. **Per-agent** tools are attached via `.defaultTools(new XxxTools())` (request-scoped, shown only on that agent). **Global** tools are `@Tool` `@Component` beans (`CurrencyTools`) — the durability layer offers them to every durable agent and they appear on every agent's registry record |
| Tool loop | automatic via `ToolCallingAdvisor` (re-enters until the model stops calling tools) |
| Orchestration | plain-Java composition in `TravelOrchestrationService` (sequential/parallel/loop/conditional/nested) |
| LLM | OpenAI `gpt-4o-mini` via `spring-ai-starter-model-openai` |
| Durability / memory / registry | off by default; opt in via the `dapr` / `memory` / `registry` Spring profiles (see [Running](#running)) |

## Architecture

```
travelPlanner (nested)
 1. parallel research          ← flight + hotel + activity concurrently (CompletableFuture)
    ├── FlightFinder   (FlightTools)
    ├── HotelFinder    (HotelTools)
    └── ActivityPlanner (ActivityTools)
 2. ItineraryFormatter         ← combines results into an itinerary (LLM-only)
```

### Agents (`agents/`)

| Agent | Tools | Method |
|-------|-------|--------|
| WeatherAssistant | WeatherTools.getWeather | `checkWeather(city)` |
| CityGuide | findAttractions, searchRestaurants, getTransportInfo | `createGuide(city,cuisine,days)` |
| FlightFinder | FlightTools.searchFlights | `findFlights(origin,destination,date)` |
| HotelFinder | HotelTools.searchHotels | `findHotels(destination,date,nights)` |
| ActivityPlanner | ActivityTools.searchActivities | `planActivities(destination,interests)` |
| ItineraryFormatter | (none) | `format(flights,hotels,activities)` |

### Orchestration (`TravelOrchestrationService`)

| Pattern | Method | Composition |
|---------|--------|-------------|
| Sequential | `tripPrep` | weather → city guide |
| Parallel | `quickResearch` | weather + city guide concurrently, merged |
| Loop | `itineraryRefiner` | weather → guide, repeated twice |
| Conditional | `travelRouter` | days≤1 → weather only; days>1 → weather + guide |
| Nested | `travelPlanner` | parallel flight/hotel/activity research → formatter |

### Wiring patterns — auto vs manual

The library is drop-in: build a `ChatClient` from the injected `ChatClient.Builder` and durability
(and, for a `@Bean`, registry) attach automatically. But every layer also has a **manual** escape
hatch for when you can't use the managed builder. This module shows a bit of each — the `agents/`
agents use the automatic path; the three in **`agents/manual/`** use the manual one:

| Agent(s) | Declared as | Durability | Registry | Memory |
|----------|-------------|-----------|----------|--------|
| WeatherAssistant, Flight/Hotel/Activity, CityGuide, ItineraryFormatter, BookingAgent | `@Component` from injected `Builder` | auto | — | — |
| `packingAssistant`, `budgetAdvisor` (`RegistryAgents`) | `@Bean ChatClient` | auto | **auto** (bean post-processor) | — |
| TravelConcierge | `@Component` | auto | — | **auto** (`ChatMemory` bean) |
| **ManualDurabilityAgent** | `@Component`, static `ChatClient.builder(model)` | **manual** — inject `DurableAdvisor`, `.defaultAdvisors(it)` | — | — |
| **ManualRegistryAgent** | `@Component` (client is a field, not a `@Bean`) | auto | **manual** — `new AgentRegisteringAdvisor(name, registrar, factory)` | — |
| **DurableMemoryConcierge** | `@Component` | auto | — | **manual** — `MessageWindowChatMemory` on the injected Dapr `ChatMemoryRepository` |

The three manual agents are `@Profile`-gated (they depend on Dapr-only beans), so enable the matching
profile — `make run-dapr` turns on all three. Exercise them with the `make test-manual-*` targets
(each endpoint returns a hint if its profile is off):

```bash
make test-manual-durability   # runs as a durable workflow via a hand-attached advisor   (needs: dapr)
make test-manual-registry     # a non-bean agent that registers itself as "visaAdvisor"   (needs: registry)
make test-manual-memory       # Dapr-backed MessageWindowChatMemory built by hand          (needs: memory)
```

## Prerequisites

- **Java 21** (LTS; enables virtual threads for the blocking durable call — see `.sdkmanrc`), **Maven 3.9+**
- An OpenAI API key, or [Ollama](https://ollama.ai/) for a local model
- **Dapr CLI** — only for the `dapr` / `memory` / `registry` profiles (not needed for pure runs)

## Running

By default this is **pure Spring AI — no Dapr, no sidecar**:

```bash
export OPENAI_API_KEY=sk-...
make run                 # or `make run-ollama` for a local model (no key needed)
```

Opt into Dapr with Spring profiles (`application-{dapr,memory,registry}.properties`), which need a
Dapr sidecar. Use **two terminals**:

```bash
# terminal 1 — the Dapr sidecar (app-id travel-planner-spring-ai-dapr, gRPC 40001 / HTTP 3500,
#              with the Redis state stores in ./components)
make dapr

# terminal 2 — the app with the full Dapr stack (durable workflows + memory + registry)
export OPENAI_API_KEY=sk-...
make run-dapr            # = mvn spring-boot:run -Dspring-boot.run.profiles=dapr,memory,registry
```

Trim or combine the profiles to taste (e.g. `-Dspring-boot.run.profiles=dapr` for durability only) —
Spring activates every profile in the comma-separated list, and these are designed to compose. Two
additional profiles select the **LLM backend** (they flip `spring.ai.model.chat`, so activate at most
one of them; if both are listed, the last wins):

```bash
# LLM through the sidecar's Conversation API (no provider SDK config in the app) — needs `make dapr`
make run-conversation        # = -Dspring-boot.run.profiles=conversation

# everything: durable workflows + memory + registry, LLM via the sidecar too
make run-dapr-conversation   # = -Dspring-boot.run.profiles=dapr,memory,registry,conversation
```

The app
listens on `:8080` either way (the `make test-*` targets below exercise the endpoints). Under the `dapr`
profile each agent call executes as a durable Dapr Workflow, with state persisted in Redis (the
shared `dapr_redis` container) — inspectable in the **Diagrid dashboard** and via
`dapr workflow list -a travel-planner-spring-ai-dapr`. Clear it with `make redis-flush`.

### Endpoints

| Endpoint | Orchestration | Pattern |
|----------|------|------|
| `make test-weather` | WeatherAssistant | single agent (1 tool) |
| `make test-guide` | CityGuide | single agent (3 tools) |
| `make test-trip` | tripPrep | sequential |
| `make test-research` | quickResearch | parallel |
| `make test-refine` | itineraryRefiner | loop (2 iterations) |
| `make test-route-quick` | travelRouter | conditional (days≤1 → weather only) |
| `make test-route-long` | travelRouter | conditional (days>1 → weather + guide) |
| `make test-travel` | travelPlanner | nested parallel research + formatter |
| `make test-chat` | TravelConcierge | multi-turn chat with per-conversation memory |
| `make test-chat-isolated` | TravelConcierge | a different conversationId has no shared memory |
| `make test-manual-durability` | ManualDurabilityAgent | manual durability — needs the `dapr` profile |
| `make test-manual-registry` | ManualRegistryAgent | manual registry — needs the `registry` profile |
| `make test-manual-memory` | DurableMemoryConcierge | manual Dapr-backed memory — needs the `memory` profile |
| `make test-crash` | CrashRecoveryAgent | crash recovery — kill the worker mid-call, re-attach by instance id (needs `dapr`) |

```bash
curl "http://localhost:8080/travel/plan?origin=NYC&destination=Paris&date=2025-07-01&nights=5&interests=history,food"
```

## Conversation memory (conversationId)

`TravelConcierge` is a memory-backed conversational agent. Its `ChatClient` registers a
`MessageChatMemoryAdvisor` over the auto-configured `ChatMemory` (in-process by default;
Dapr-state-backed under the `memory` profile), and each request carries a `conversationId` via
`ChatMemory.CONVERSATION_ID`:

```
GET /chat?message=<text>[&conversationId=<id>]
```

- `conversationId` is **optional**: omit it and the server assigns a fresh one. Either way the
  effective id is returned in the **`X-Conversation-Id`** response header, so a client can capture
  it and reuse it on the next turn without hard-coding one.
- Requests with the **same** `conversationId` share history — the concierge recalls what you told
  it earlier (destination, interests, dates) instead of asking again.
- **Different** `conversationId`s are isolated.

> `conversationId` is chat-memory grouping only — **not** the durability key. Every call still runs
> under its own random workflow instance id (dapr-agents parity).

```bash
# turn 1 — no conversationId: the server assigns one; grab it from the response header
cid=$(curl -sS -D - -o /dev/null -G localhost:8080/chat \
  --data-urlencode "message=I am planning a 4-day trip to Tokyo and I love food and tech." \
  | awk 'tolower($1)=="x-conversation-id:"{print $2}' | tr -d '\r')
# turn 2 — reuse it: recalls "Tokyo" without being told again
curl -G localhost:8080/chat --data-urlencode "conversationId=$cid" \
  --data-urlencode "message=What is the weather like there?"
# different conversation — no memory of the first trip
curl -G localhost:8080/chat --data-urlencode conversationId=bob \
  --data-urlencode "message=Where am I going on my trip?"
```

`make test-chat` runs the 3-turn recall flow — turn 1 omits `conversationId`, captures the
server-assigned id from the `X-Conversation-Id` header into `.last-conversation-id`, and reuses it
for turns 2–3; `make test-chat-isolated` shows the isolation. Without a profile the history is
in-process; under the `memory` profile the `dapr-spring-ai-memory` starter backs `ChatMemory` with a
Dapr state store (`agent-memory`), so conversations persist and survive restarts — inspect them with
`make memory-list`.

## Crash recovery (kill the worker mid-call)

The durable path survives a hard crash of the app — which also hosts the in-process workflow worker.
An in-flight `ChatClient.call()` resumes from its history on restart (completed LLM turns and tool
calls are **not** re-run), and — because you scheduled it under an id **you** own — you re-attach to
the resumed run and collect its result by simply re-issuing the same call.

The `crashRecoveryAgent` books a reservation with a deliberately slow tool (~30s), and schedules the
call under a caller-chosen id via `DurableAdvisor.INSTANCE_ID_KEY`. It is a **named `ChatClient` bean**
(`CrashRecoveryAgentConfig`), so it runs under its own per-agent workflow name
`spring-ai.crashRecoveryAgent.workflow` (visible in the dashboard / `dapr workflow list`) rather than
the generic `spring-ai.workflow` — the name is assigned at build time by the bean name, and the worker
only registers per-agent names for ChatClient *beans*. The tool (`SlowBookingTools`) is a **global
`@Tool` bean** — not a per-call `.defaultTools(...)` tool — precisely so it is rediscovered on the
fresh worker after the crash and the resumed activity can run it (a request-scoped tool would be gone
after restart; this is the agent-scoped-vs-crash-safe tradeoff, see the library README):

```
GET  /crash/book?id=<your-id>&reference=<ref>   # book under your id (a repeat with it = attach)
POST /crash/kill                                # SIGKILL the app — demo only
```

Needs the `dapr` profile + a sidecar. Step by step (start with `make run-dapr`, sidecar `make dapr`):

```bash
# 1. Terminal A — book under an id you own. Blocks ~30s while the tool "commits"; watch the app log
#    for ">>> commitReservation(...) — committing over ~30s".
make test-crash                 # GET /crash/book?id=trip-42&reference=ABC123

# 2. Terminal B — during that window, SIGKILL the app (in-process worker + blocked caller both die):
make crash-kill                 # POST /crash/kill  → the app process dies (curl sees a reset)

# 3. Restart the app: make run-dapr
#    The durable runtime resumes instance trip-42; the pre-crash LLM turn is not re-executed.

# 4. Terminal A — re-issue the SAME call. It ATTACHES to the resumed run (waits if it is still
#    committing, or returns the recorded answer if it already finished) — no second booking:
make test-crash                 # same id → the SAME confirmation code
```

The confirmation code is derived from the reference, so an identical code on the re-issue is visible
proof the booking was not redone. Because the re-issue is a normal successful call, the whole advisor
chain applies (chat memory, etc.). Inspect the resumed run with `dapr workflow` or the Diagrid
dashboard to see the completed activity was not re-executed.

> The instance id is a **bearer handle** — an app exposing a retry endpoint should guard it like a
> primary key. To re-run a spent id, purge it first (`DaprWorkflowClient.purgeWorkflow(id)`); a
> purged/absent id runs fresh. Note this `id` is the **durability** instance id
> (`DurableAdvisor.INSTANCE_ID_KEY`), unlike the `conversationId` above (chat-memory grouping) — it is
> the retry/recovery contract from the library README's "Choosing your own instance id".

## Observability (distributed tracing)

The `dapr-spring-ai` durability layer is instrumented with Micrometer, but it **no-ops** unless the
app provides a tracing backend — so the base example ships without one. Opt in with the `-Ptracing`
Maven profile (adds Spring Boot tracing + an OpenTelemetry→OTLP exporter) and the `tracing` Spring
profile (sampling + OTLP endpoint + MDC log pattern). One request then produces a single trace
spanning the caller thread and the workflow worker, **plus** the Dapr sidecar's own workflow spans,
all exported to **Jaeger** over OTLP.

Three terminals:

```bash
make jaeger          # 1. local Jaeger all-in-one — UI at http://localhost:16686 (OTLP on 4317/4318)
make dapr-tracing    # 2. Dapr sidecar with tracing → the same Jaeger (--config appconfig-tracing.yaml)
export OPENAI_API_KEY=sk-...
make run-dapr-tracing # 3. the app with -Ptracing + profiles dapr,memory,registry,tracing
```

Then hit any endpoint and open **http://localhost:16686** (pick service `travel-planner-spring-ai-dapr`):

```bash
make test-travel     # nested plan: parallel flight/hotel/activity research → formatter
```

You'll see one trace shaped like:

```
travel.plan                             ← orchestration root span (opened by TravelOrchestrationService)
 ├─ dapr.springai.durable.call          ← flight agent's ChatClient.call() (parallel branch)
 │   ├─ dapr.springai.llm.invoke        ← worker thread (a model turn)
 │   │   └─ chat gpt-4o-mini            ← Spring AI's own gen_ai span, parented for free
 │   └─ dapr.springai.tool.invoke       ← searchFlights (worker)
 ├─ dapr.springai.durable.call          ← hotel agent (parallel branch)
 ├─ dapr.springai.durable.call          ← activity agent (parallel branch)
 └─ dapr.springai.durable.call          ← itinerary formatter
```

Each multi-agent endpoint opens a **named root span** (`travel.plan`, `travel.research`, …) in
`TravelOrchestrationService`, so a whole request is one trace with every agent call nested under it —
this is more reliable than depending on Boot's servlet HTTP-server span (which is named `http get`,
not per-route). The activity/`gen_ai` spans nest even though they run on the workflow worker: the
caller's W3C context rides through the workflow input and is restored activity-side. The Dapr
sidecar's own workflow-orchestration spans (`StartInstance → orchestration||… → activity||…`) also
join this trace — see the note below.

Getting the *parallel* branches to join that root takes one more thing: `travelPlanner` fans its
agents out across a thread pool, so the pool is wrapped with Micrometer's `ContextExecutorService` —
otherwise the trace context wouldn't cross the thread boundary and each parallel agent call would
start its own root trace. (An application-layer concern: any code handing agent calls to another
thread must propagate context; sequential calls nest automatically.)

> **App and sidecar in one trace (needs the observed workflow client).** dapr-spring-ai's starter
> auto-configures Dapr's `ObservationDaprWorkflowClient` (from `dapr-spring-boot-observation`) whenever
> an `ObservationRegistry` is present. That client propagates the caller's W3C trace context to the
> sidecar when scheduling, so the sidecar's `durabletask` spans nest under the app's `travel.plan`
> trace instead of forming a separate one. Each run still carries the workflow **instance id** (a
> random UUID, echoed as `dapr.spring-ai.instance-id`) as a fallback correlation key. Requires
> dapr-sdk-workflows ≥ 1.18 and a sidecar that honors the propagated context.
>
> Also note: Boot 4 doesn't derive the OTel `service.name` from `spring.application.name`, so
> `application-tracing.properties` pins it — without that the app's spans land under
> `unknown_service:java` instead of `travel-planner-spring-ai-dapr` (the name the sidecar uses).

Two things work regardless of the trace tree:

- **Instance id on the response.** Every successful call echoes `dapr.spring-ai.instance-id` /
  `dapr.spring-ai.workflow-name` into `ChatResponse.getMetadata()` — the handle you search Jaeger by.
- **Log correlation.** The activity/model log lines carry the instance id and tool name via MDC
  (`[instance=…] [tool=…]`), so you can follow one call through the log even with no collector.

Stop the collector with `make jaeger-stop`. Without `-Ptracing` the app has no `Tracer` bean and the
durable path is a pure no-op (zero overhead) — a live demo of the starter's graceful degradation.

## LLM Provider Configuration

Two chat models are on the classpath, selected by `spring.ai.model.chat` (Spring AI's standard
provider switch); the `openai` and `conversation` Spring profiles flip it:

- **`openai` (the committed default, also without any profile)** — the in-app OpenAI SDK,
  configured in `application.properties` (`spring.ai.openai.*`): OpenAI `gpt-4o-mini`, key from
  `${OPENAI_API_KEY}`. To target a different OpenAI-compatible endpoint (e.g. Ollama), set
  `spring.ai.openai.base-url` — note it must end in `/v1`, since Spring AI 2.0's underlying
  `openai-java` SDK appends `/chat/completions` to the base-url.
- **`conversation`** — the `dapr-spring-ai-conversation` ChatModel: LLM traffic leaves the app as a
  Dapr Conversation API call and the **sidecar** talks to the provider, configured by the `llm`
  component (`components/llm.yaml`, key via the env secret store). No provider SDK config in the
  app; swap providers by editing the component YAML. Requires a sidecar (`make dapr`).

The LLM profiles compose with the concern profiles (`dapr`, `memory`, `registry`, `tracing`) —
`make run-dapr-conversation` runs durable workflows whose LLM activity also goes through the
sidecar. Activate only one LLM profile at a time: both set the same selector, so listing both just
means the last one wins.

## Project Structure

```
travel-planner/
├── pom.xml                 Spring Boot 4.0.5 parent, Java 21, spring-ai 2.0.0 + the 3 dapr-spring-ai starters; `-Ptracing` profile
├── .sdkmanrc               java=21.0.11-tem, maven=3.9.0
├── Makefile                run (pure) / run-dapr / run-ollama / run-conversation / run-dapr-conversation / jaeger / dapr-tracing / run-dapr-tracing / test-* / *-list
├── components/             kvstore (workflow actor store) + agent-registry + agent-memory (Redis) + llm (conversation.openai) + env secret store
├── appconfig.yaml          Dapr Configuration: workflow state-retention policy (Catalyst)
├── appconfig-tracing.yaml  Dapr Configuration for the tracing demo: sidecar spans → local Jaeger (OTLP)
├── travel-planner-dev.yaml Diagrid Catalyst dev-run file (diagrid dev run)
├── src/main/resources/
│   ├── application.properties                                   pure defaults (Dapr off) + profile usage
│   └── application-{dapr,memory,registry,tracing,openai,conversation}.properties    opt-in profiles
└── src/main/java/io/diagrid/springai/examples/travelplanner/
    ├── TravelPlannerApplication.java
    ├── tools/              8 @Tool classes: 6 request-scoped (mock data + FlakyApiTools) + 2 global @Component (CurrencyTools, SlowBookingTools) offered to every durable agent (so they survive a worker restart)
    ├── agents/             8 ChatClient @Component agents (incl. TravelConcierge, BookingAgent) + CrashRecoveryAgentConfig (the named `crashRecoveryAgent` @Bean → its own per-agent workflow name)
    │   └── manual/         manual-wiring agents: ManualDurabilityAgent, ManualRegistryAgent, DurableMemoryConcierge
    ├── registry/           RegistryAgents (ChatClient @Bean agents, discovered by the registry)
    ├── orchestration/      TravelOrchestrationService (app-layer patterns)
    └── web/                TravelControllers + ConciergeController + RetryController + CrashRecoveryController + RegistryAgentController + ManualWiringController
```
