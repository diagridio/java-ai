# durable-chat example

A plain Spring AI app — `ChatClient` + one `@Tool` bean + a REST endpoint — that becomes
**durable across restarts** purely by having the `diagrid-spring-ai-starter` on the classpath.
There is no durability code in this app; look at `ChatController` and `BookingTools`.

## Prerequisites

- [Diagrid CLI](https://docs.diagrid.io/catalyst/references/cli-reference/overview)
- JDK 17+ and Maven 3.9+
- An [OpenAI API key](https://platform.openai.com/api-keys)

## Build

From the `diagrid-spring-ai` directory (installs the parent, core, and starter), then this example:

```bash
cd ..                       # diagrid-spring-ai/
mvn install -DskipTests
cd examples/durable-chat
mvn package -DskipTests
```

## Run on Catalyst

Catalyst runs the app against a **managed** data plane — no sidecar, no state store to stand up.
`--enable-managed-workflow` provisions the workflow store the durable path needs.

```bash
export OPENAI_API_KEY=sk-...

diagrid login
diagrid project create durable-chat --enable-managed-workflow --wait --use
diagrid dev run -f durable-chat-dev.yaml --approve
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
a completed step is never re-run on recovery. Note there is **no** reissue dedup: re-issue the same
request and it starts a *new* workflow and books *again* (`bookings.log` gets a second line). Making a
re-submit idempotent is the tool's job — key off a business value in the tool's arguments (here, the
booking reference); the library adds no dedup of its own.

> Prefer a local model? Point the OpenAI SDK at a local [Ollama](https://ollama.com) by overriding
> `spring.ai.openai.base-url` (see the commented block in `application.properties`) — no key needed.

## Test durability across a crash

The durable path survives a hard crash of the app (which also hosts the in-process workflow worker):
an in-flight `ChatClient.call()` resumes from its checkpoint on restart, and the completed model call
and tool call are **not** re-run. On Catalyst the workflow store is managed, so it outlives the app —
just kill and restart:

1. Fire a request, then **kill the app** (Ctrl-C the `diagrid dev run`, or `kill -9` the JVM) while or
   right after it processes.
2. Restart with `diagrid dev run -f durable-chat-dev.yaml --approve`. The in-flight workflow resumes
   from its last checkpoint; `bookings.log` contains the booking **exactly once**, and the completed
   model call is not repeated.

## How it works

- `diagrid-spring-ai-starter` auto-configures a `DurableAdvisor` (added to every `ChatClient`) and an
  in-process Dapr Workflow worker.
- The advisor turns each `ChatClient.call()` into a workflow under a fresh random instance id
  (no dedup; a retried call is a new execution). If a call's wait budget
  elapses it throws `DurableCallTimeoutException` with the instance id, so the still-running result
  can be collected later by id.
- The workflow runs the model call and each tool call as separate activities, so a crash resumes
  from the last completed step. Your `@Tool` beans are discovered and dispatched automatically.
