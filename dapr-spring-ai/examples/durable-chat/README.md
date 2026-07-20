# durable-chat example

A plain Spring AI app — `ChatClient` + one `@Tool` bean + a REST endpoint — that becomes
**durable across restarts** purely by having the `dapr-spring-ai-starter` on the classpath.
There is no durability code in this app; look at `ChatController` and `BookingTools`.

## Prerequisites

- Java 17+ and Maven
- [Dapr CLI](https://docs.dapr.io/getting-started/install-dapr-cli/) initialized: `dapr init`
  (provides the placement + scheduler services the workflow engine needs)
- [Ollama](https://ollama.com) running with a tool-capable model:
  `ollama pull llama3.1:8b`

## Build

From the `dapr-spring-ai` directory (installs the parent, core, and starter), then this example:

```bash
cd ..                       # dapr-spring-ai/
mvn install -DskipTests
cd examples/durable-chat
mvn package -DskipTests
```

## Run (smoke test — see it work)

`dapr run` starts the sidecar (with the in-memory actor state store in `./components`) alongside the app:

```bash
dapr run \
  --app-id durable-chat \
  --app-port 8080 \
  --dapr-grpc-port 50001 \
  --resources-path ./components \
  -- mvn spring-boot:run
```

In another terminal:

```bash
curl -s -X POST "http://localhost:8080/chat?message=Book%20me%20a%20flight%20to%20Madrid&conversationId=trip-1"
# -> "Flight to Madrid is booked. Confirmation code BK-....."

cat bookings.log
# -> booked flight to Madrid -> BK-....   (exactly one line)
```

Every call ran as a Dapr Workflow: the model call and the `bookFlight` tool each executed as a
checkpointed activity. That checkpointing is what survives a mid-flight worker crash (next section) —
a completed step is never re-run on recovery. Note there is **no** reissue dedup:
re-issue the same request and it starts a *new* workflow and books *again* (`bookings.log` gets a
second line). Making a re-submit idempotent is the tool's job, via the activity `taskExecutionId`.

## Test durability across a crash

To prove crash recovery you need the sidecar to outlive the app, so run them separately.

1. Start a standalone sidecar (no app) that keeps running:

   ```bash
   dapr run --app-id durable-chat --dapr-grpc-port 50001 --resources-path ./components -- sleep infinity
   ```

2. Start the app pointed at that sidecar:

   ```bash
   DAPR_GRPC_ENDPOINT=localhost:50001 mvn spring-boot:run
   ```

3. Fire a request, then **kill the app JVM** (`kill -9 <pid>` / Ctrl-C the `spring-boot:run`)
   while or right after it processes. The workflow keeps living in the sidecar.

4. Restart the app (step 2 again) and re-issue the **same** request:

   ```bash
   curl -s -X POST "http://localhost:8080/chat?message=Book%20me%20a%20flight%20to%20Oslo&conversationId=trip-2"
   ```

   The workflow resumes from its last checkpoint; `bookings.log` contains the Oslo booking
   **exactly once**, and the completed model call is not repeated.

## How it works

- `dapr-spring-ai-starter` auto-configures a `DurableAdvisor` (added to every `ChatClient`) and an
  in-process Dapr Workflow worker.
- The advisor turns each `ChatClient.call()` into a workflow under a fresh random instance id
  (no dedup; a retried call is a new execution). If a call's wait budget
  elapses it throws `DurableCallTimeoutException` with the instance id, so the still-running result
  can be collected later by id.
- The workflow runs the model call and each tool call as separate activities, so a crash resumes
  from the last completed step. Your `@Tool` beans are discovered and dispatched automatically.
