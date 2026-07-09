APP_ID    := travel-planner-spring-ai-dapr
DAPR_GRPC := 40001
DAPR_HTTP := 3500
PROJECT   ?=
MSG       ?= Where am I going on my trip?

.PHONY: dapr dapr-tracing run run-dapr run-dapr-tracing run-ollama run-conversation \
        run-dapr-conversation jaeger jaeger-stop \
        test-weather test-guide test-trip test-research test-refine \
        test-route-quick test-route-long test-travel test-chat test-chat-isolated test-retry \
        test-packing test-budget registry-list memory-list catalyst-config redis-flush clean

## Start the Dapr sidecar standalone (separate terminal). Export OPENAI_API_KEY first.
## The dapr-spring-ai starter runs the ChatClient agents as durable Dapr Workflows;
## ./components provides the Redis actor state store the workflow engine needs (shared dapr_redis).
dapr:
	dapr run \
		--app-id $(APP_ID) \
		--dapr-grpc-port $(DAPR_GRPC) \
		--dapr-http-port $(DAPR_HTTP) \
		--resources-path ./components \
		--log-level info

## Run as pure Spring AI — no Dapr, no sidecar needed (default profile).
run:
	mvn spring-boot:run

## Run the full Dapr stack (durable workflows + memory + registry). Needs `make dapr` in another
## terminal. Trim/combine profiles freely, e.g. -Dspring-boot.run.profiles=dapr for durability only.
run-dapr:
	DAPR_GRPC_PORT=$(DAPR_GRPC) DAPR_HTTP_PORT=$(DAPR_HTTP) mvn spring-boot:run \
		-Dspring-boot.run.profiles=dapr,memory,registry

## LLM through the Dapr sidecar's Conversation API (dapr-spring-ai-conversation) instead of the
## in-app OpenAI SDK: the `llm` component in ./components picks the provider, the app holds no
## provider config. Needs `make dapr` in another terminal (with OPENAI_API_KEY exported there).
run-conversation:
	DAPR_GRPC_PORT=$(DAPR_GRPC) DAPR_HTTP_PORT=$(DAPR_HTTP) mvn spring-boot:run \
		-Dspring-boot.run.profiles=conversation

## The full Dapr story: durable workflows + memory + registry, with the LLM activity itself also
## calling through the sidecar's Conversation API. Needs `make dapr` in another terminal.
run-dapr-conversation:
	DAPR_GRPC_PORT=$(DAPR_GRPC) DAPR_HTTP_PORT=$(DAPR_HTTP) mvn spring-boot:run \
		-Dspring-boot.run.profiles=dapr,memory,registry,conversation

## Pure Spring AI against a local Ollama instead of OpenAI (no key needed, no sidecar).
run-ollama:
	OPENAI_API_KEY=ollama mvn spring-boot:run \
		-Dspring-boot.run.arguments="--spring.ai.openai.base-url=http://localhost:11434/v1 --spring.ai.openai.chat.model=llama3.1:8b"

## ── Observability demo (see the README's "Observability" section) ──
## End-to-end tracing: caller call → llm/tool activity spans → Spring AI gen_ai span, plus the Dapr
## sidecar's workflow spans, all in one Jaeger. Three terminals: `make jaeger`, `make dapr-tracing`,
## `make run-dapr-tracing` — then hit any endpoint and open http://localhost:16686.

## Local Jaeger all-in-one (UI at http://localhost:16686, OTLP receivers on 4317/4318).
jaeger:
	docker run -d --rm --name jaeger \
		-e COLLECTOR_OTLP_ENABLED=true \
		-p 16686:16686 -p 4317:4317 -p 4318:4318 \
		jaegertracing/all-in-one:latest

jaeger-stop:
	-docker stop jaeger

## Dapr sidecar WITH tracing → the same Jaeger via OTLP (adds --config appconfig-tracing.yaml). Use
## instead of `make dapr` for the tracing demo.
dapr-tracing:
	dapr run \
		--app-id $(APP_ID) \
		--dapr-grpc-port $(DAPR_GRPC) \
		--dapr-http-port $(DAPR_HTTP) \
		--resources-path ./components \
		--config appconfig-tracing.yaml \
		--log-level info

## App with the full Dapr stack + tracing (OpenTelemetry→OTLP→Jaeger). `-Ptracing` adds the deps; the
## `tracing` Spring profile adds the config. Needs `make jaeger` + `make dapr-tracing`.
run-dapr-tracing:
	DAPR_GRPC_PORT=$(DAPR_GRPC) DAPR_HTTP_PORT=$(DAPR_HTTP) mvn spring-boot:run -Ptracing \
		-Dspring-boot.run.profiles=dapr,memory,registry,tracing

## ── Quick test endpoints (same URLs as the other travel-planner modules) ──
test-weather:
	curl -s "http://localhost:8080/weather?city=Paris"

test-guide:
	curl -s "http://localhost:8080/guide?city=Paris&cuisine=french&days=3"

test-trip:
	curl -s "http://localhost:8080/trip?city=Paris&cuisine=french&days=3"

test-research:
	curl -s "http://localhost:8080/research?city=Paris&cuisine=french&days=3"

test-refine:
	curl -s "http://localhost:8080/refine?city=Paris&cuisine=french&days=3"

test-route-quick:
	curl -s "http://localhost:8080/route?city=Paris&days=1"

test-route-long:
	curl -s "http://localhost:8080/route?city=Paris&cuisine=french&days=3"

test-travel:
	curl -s "http://localhost:8080/travel/plan?origin=NYC&destination=Paris&date=2025-07-01&nights=5&interests=history,food"

## Conversation-memory demo: a 3-turn chat. Turn 1 omits conversationId, so the server assigns one
## and returns it in the X-Conversation-Id header; we capture it into .last-conversation-id and reuse
## it for turns 2 and 3, which rely on memory of the destination/interests set in turn 1.
CID_FILE = .last-conversation-id
test-chat:
	@echo "— turn 1 (new conversation): set context —"
	@curl -s -D /tmp/tp-chat-headers -G "http://localhost:8080/chat" --data-urlencode "message=I am planning a 4-day trip to Tokyo and I love food and tech."; echo
	@grep -i '^X-Conversation-Id:' /tmp/tp-chat-headers | awk '{print $$2}' | tr -d '\r' > $(CID_FILE)
	@echo "  (server assigned conversationId=$$(cat $(CID_FILE)) → saved to $(CID_FILE))"
	@echo "— turn 2 (same conversation): should recall Tokyo —"
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=$$(cat $(CID_FILE))" --data-urlencode "message=What is the weather like there?"; echo
	@echo "— turn 3 (same conversation): should recall Tokyo + interests —"
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=$$(cat $(CID_FILE))" --data-urlencode "message=What should I do?"; echo

## Isolation: a different conversationId ("bob") has no memory of the saved conversation's trip.
## Override the prompt with MSG="..." (e.g. make test-chat-isolated MSG="What's my name?").
test-chat-isolated:
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=bob" --data-urlencode "message=$(MSG)"; echo

## Retry demo: BookingAgent's tool throws on every odd invocation. Each tool call is a durable
## activity, so the runtime retries it instead of failing the workflow. Every call starts on an odd
## attempt that throws, is retried, and the even attempt succeeds — so the workflow still completes.
test-retry:
	@echo "— odd attempt throws → runtime retries → even attempt succeeds → completes —"
	@curl -s "http://localhost:8080/retry-test?reference=ABC123"; echo

## Agent-registry demo: two ChatClient *beans* (packingAssistant has a tool, budgetAdvisor has
## none). Both are registered with a thin record at startup; calling each endpoint enriches the
## record with the system prompt + advertised tools (packingAssistant gains getWeather).
test-packing:
	@curl -s "http://localhost:8080/packing?city=Tokyo"; echo

test-budget:
	@curl -s "http://localhost:8080/budget?city=Tokyo&days=4"; echo

## Manual-wiring demos (agents/manual). Each needs the matching profile — `make run-dapr` enables all
## three. ManualDurabilityAgent attaches the durable advisor by hand; ManualRegistryAgent registers a
## non-bean agent ("visaAdvisor") by hand; DurableMemoryConcierge builds Dapr-backed memory by hand.
test-manual-durability:
	@curl -s "http://localhost:8080/manual/durability?origin=SFO&destination=NRT&date=2026-08-01"; echo

test-manual-registry:
	@curl -s "http://localhost:8080/manual/registry?nationality=Spanish&destination=Japan"; echo

test-manual-memory:
	@curl -s -G "http://localhost:8080/manual/memory" --data-urlencode "conversationId=carol" --data-urlencode "message=I am going to Lisbon in July"; echo

## List the agent-registry records written to Redis (Dapr prefixes keys with the app-id).
registry-list:
	@docker exec dapr_redis redis-cli KEYS '*agents*'

## Show the persisted chat-memory conversations, stored in the agent-memory store in Dapr Agents
## key format {agentName}:_memory_{conversationId} (e.g. default:_memory_<id>) — matching on the
## _memory_ marker excludes the workflow state keys, which embed the same conversationId. Survives
## restarts. Inspect a record's content with: docker exec dapr_redis redis-cli HGETALL '<key>'.
memory-list:
	@docker exec dapr_redis redis-cli KEYS '*' | grep '_memory_' || echo "(no conversation memory yet — run make test-chat)"

## Wire the workflow retention policy into Catalyst: apply the Dapr Configuration, then point the
## App ID at it. The App ID references the config via --app-config; the dev-run file can't carry it,
## so this must be set on the App ID (re-run after a `diagrid dev run` if the association is lost).
## Restart the app afterwards so the sidecar picks up the config. Select a project first
## (diagrid project use <name>) or pass PROJECT=<name>.
catalyst-config:
	diagrid apply -f appconfig.yaml $(if $(PROJECT),--project $(PROJECT))
	diagrid appid update $(APP_ID) --app-config appconfig $(if $(PROJECT),--project $(PROJECT))

## Flush the Dapr workflow/actor state from Redis (the shared dapr_redis container from `dapr init`).
redis-flush:
	docker exec dapr_redis redis-cli FLUSHDB

clean:
	mvn clean -q
