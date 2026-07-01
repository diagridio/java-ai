# Travel Planner — Spring AI 2.0 (optional Dapr)

> **Pure Spring AI 2.0 by default; Dapr is opt-in via Spring profiles.** With no profile active the
> app is plain Spring AI — no Dapr, no sidecar. Activating the `dapr`, `memory`, and/or `registry`
> profiles layers in the `io.diagrid:dapr-spring-ai-*` starters with **no application-code changes**
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
| Tools | `@Tool` / `@ToolParam` methods (`org.springframework.ai.tool.annotation.*`); JSON schema auto-generated |
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

Trim the profiles to taste (e.g. `-Dspring-boot.run.profiles=dapr` for durability only). The app
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

```bash
curl "http://localhost:8080/travel/plan?origin=NYC&destination=Paris&date=2025-07-01&nights=5&interests=history,food"
```

## Conversation memory (conversationId)

`TravelConcierge` is a memory-backed conversational agent. Its `ChatClient` registers a
`MessageChatMemoryAdvisor` over the auto-configured `ChatMemory` (in-process by default;
Dapr-state-backed under the `memory` profile), and each request carries a `conversationId` via
`ChatMemory.CONVERSATION_ID`:

```
GET /chat?conversationId=<id>&message=<text>
```

- Requests with the **same** `conversationId` share history — the concierge recalls what you told
  it earlier (destination, interests, dates) instead of asking again.
- **Different** `conversationId`s are isolated.

```bash
# turn 1 — set context
curl -G localhost:8080/chat --data-urlencode conversationId=alice \
  --data-urlencode "message=I am planning a 4-day trip to Tokyo and I love food and tech."
# turn 2 — recalls "Tokyo" without being told again
curl -G localhost:8080/chat --data-urlencode conversationId=alice \
  --data-urlencode "message=What is the weather like there?"
# different conversation — no memory of alice's trip
curl -G localhost:8080/chat --data-urlencode conversationId=bob \
  --data-urlencode "message=Where am I going on my trip?"
```

`make test-chat` runs the 3-turn recall flow; `make test-chat-isolated` shows the isolation.
Without a profile the history is in-process; under the `memory` profile the `dapr-spring-ai-memory`
starter backs `ChatMemory` with a Dapr state store (`agent-memory`), so conversations persist and
survive restarts — inspect them with `make memory-list`.

## LLM Provider Configuration

Configured in `application.properties` (`spring.ai.openai.*`): committed default is OpenAI
`gpt-4o-mini`, key from `${OPENAI_API_KEY}`. To target a different OpenAI-compatible endpoint
(e.g. Ollama), set `spring.ai.openai.base-url` — note it must end in `/v1`, since Spring AI 2.0's
underlying `openai-java` SDK appends `/chat/completions` to the base-url.

## Project Structure

```
travel-planner/
├── pom.xml                 Spring Boot 4.0.5 parent, Java 21, spring-ai 2.0.0 + the 3 dapr-spring-ai starters
├── .sdkmanrc               java=21.0.11-tem, maven=3.9.0
├── Makefile                run (pure) / run-dapr / run-ollama / test-* / registry-list / memory-list / catalyst-config
├── components/             kvstore (workflow actor store) + agent-registry + agent-memory (Redis)
├── appconfig.yaml          Dapr Configuration: workflow state-retention policy (Catalyst)
├── travel-planner-dev.yaml Diagrid Catalyst dev-run file (diagrid dev run)
├── src/main/resources/
│   ├── application.properties                          pure defaults (Dapr off) + profile usage
│   └── application-{dapr,memory,registry}.properties   opt-in profiles
└── src/main/java/io/diagrid/springai/examples/travelplanner/
    ├── TravelPlannerApplication.java
    ├── tools/              6 @Tool classes (mock data + FlakyApiTools for the retry demo)
    ├── agents/             8 ChatClient @Component agents (incl. TravelConcierge, BookingAgent)
    ├── registry/           RegistryAgents (ChatClient @Bean agents, discovered by the registry)
    ├── orchestration/      TravelOrchestrationService (app-layer patterns)
    └── web/                TravelControllers + ConciergeController + RetryController + RegistryAgentController
```
