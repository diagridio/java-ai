APP_ID    := travel-planner-spring-ai-dapr
DAPR_GRPC := 40001
DAPR_HTTP := 3500
PROJECT   ?= 

.PHONY: dapr run run-dapr run-ollama test-weather test-guide test-trip test-research test-refine \
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

## Pure Spring AI against a local Ollama instead of OpenAI (no key needed, no sidecar).
run-ollama:
	OPENAI_API_KEY=ollama mvn spring-boot:run \
		-Dspring-boot.run.arguments="--spring.ai.openai.base-url=http://localhost:11434/v1 --spring.ai.openai.chat.model=llama3.1:8b"

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

## Conversation-memory demo: a 3-turn chat keyed by conversationId=alice.
## Turns 2 and 3 rely on memory of the destination/interests set in turn 1.
test-chat:
	@echo "— turn 1 (alice): set context —"
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=alice" --data-urlencode "message=I am planning a 4-day trip to Tokyo and I love food and tech."; echo
	@echo "— turn 2 (alice): should recall Tokyo —"
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=alice" --data-urlencode "message=What is the weather like there?"; echo
	@echo "— turn 3 (alice): should recall Tokyo + interests —"
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=alice" --data-urlencode "message=What should I do?"; echo

## Isolation: a different conversationId has no memory of alice's trip.
test-chat-isolated:
	@curl -s -G "http://localhost:8080/chat" --data-urlencode "conversationId=bob" --data-urlencode "message=Where am I going on my trip?"; echo

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

## List the agent-registry records written to Redis (Dapr prefixes keys with the app-id).
registry-list:
	@docker exec dapr_redis redis-cli KEYS '*agents*'

## Show the persisted chat-memory conversations, stored in the agent-memory store in Dapr Agents
## key format {agentName}:_memory_{conversationId} (e.g. default:_memory_alice) — matching on the
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
