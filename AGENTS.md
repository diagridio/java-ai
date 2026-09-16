# AGENTS.md

Working notes for an AI assistant in this repo: the things that are easy to get wrong.
The README says what the project is; this does not repeat it.

Verified **2026-08-23** against this tree. Every `diagrid` CLI claim was checked against
the CLI at **v1.66.0** logged in to production — re-verify against a newer CLI before
trusting them.

The README's status banner is accurate and load-bearing: **early development, APIs *and
module layout* not yet stable.** The group and artifact coordinates have already been renamed
once (#39) and one module merged away (#43). Trust the source in this tree over any prose
about it, including this file.

## Layout: everything Maven happens one directory down

The git root holds only docs and `.github/`. The Maven reactor is `diagrid-spring-ai/`.

- The reactor is the parent POM `io.diagrid:diagrid-spring-ai-parent` plus **four** jar
  modules: `-core`, `-starter`, `-memory`, `-conversation`. **`agent-registry` is not a
  module** — it was merged into the starter at 0.2.0 (#43). Anything naming it as a module
  or a dependency is stale.
- `examples/travel-planner`, `examples/durable-chat` and `examples/identity` are **standalone
  apps, not reactor modules**: parented to `spring-boot-starter-parent`, and they pin the library version as
  a literal string (`0.3.0-SNAPSHOT` today). They only resolve after `mvn install` at the
  reactor root, and a version bump has to be applied to them by hand — `versions:set`
  does not reach them.
- There is no `.mvn/` directory, so `${maven.multiModuleProjectDirectory}` resolves to
  **the directory you invoke `mvn` from**. The Checkstyle/SpotBugs/PMD config paths and the
  LICENSE-bundling execution are all anchored to it, so `cd` into a module and run
  `mvn verify` and they resolve to the wrong place. Always invoke from `diagrid-spring-ai/`
  and select modules with `-pl`.
- No Maven wrapper is vendored, and `.gitignore` excludes `*.jar` at any depth, so one
  can't simply be added. Use your own `mvn` (3.9+).

## Build and test: exactly what CI gates

CI is `.github/workflows/build.yml`. Its two jobs, **`build (17)` and `build (21)`**, are the
*required status checks* on the default branch, with the strict "branch must be up to date"
policy. A PR also gets an **`Analyze (java-kotlin)`** CodeQL check that is *not* a workflow in
this repo — it is a dynamic GitHub code-scanning run, so don't go looking for its YAML, and it
is not a required check. The other two workflows (`release.yml`, `post-release.yml`) are
`workflow_dispatch` only and never fire on a PR.

```bash
cd diagrid-spring-ai
mvn -B clean install                                        # what CI runs, on JDK 17 AND 21
mvn -B -f examples/travel-planner/pom.xml clean package -DskipTests   # JDK 21 leg only
mvn -B -f examples/durable-chat/pom.xml  clean package -DskipTests   # JDK 21 leg only
mvn -B -f examples/identity/pom.xml      clean package -DskipTests   # JDK 21 leg only
```

- `install`, not `verify`, is deliberate: the examples are not reactor modules, so they
  resolve the freshly built SNAPSHOT from the local repo.
- The example compile step is the **anti-bit-rot gate** and it runs on the JDK 21 leg only —
  `travel-planner` sets `java.version=21` and cannot build on 17 (`durable-chat` is 17). No
  API keys are needed; model credentials are read at runtime.
- Unit tests are Surefire only. `dsa.excludedGroups=probe,integration` keeps the JUnit tags
  `probe` and `integration` out of the default run. To run a probe, name it and clear the
  property: `mvn -pl diagrid-spring-ai-core test -Dtest=ToolExecutionProbe -Ddsa.excludedGroups=`.
- Beyond CI, the review gates on the default branch are: PR required with 1 approval,
  review threads resolved, linear history, and **signed commits** on `main` (a squash merge
  satisfies that). Force-push and deletion are blocked.

### The crash-recovery integration test

`CrashRecoveryIT` is this repo's actual proof of durability and is **not** in CI:

```bash
cd diagrid-spring-ai && mvn -pl diagrid-spring-ai-core -Pintegration verify
```

It needs Docker (a `daprio/daprd:1.18.0` sidecar via Testcontainers) **and** a local Ollama
serving `llama3.1:8b`; without Ollama it `assumeTrue`-skips rather than failing. The
`integration` profile exists because Surefire's default patterns match `*Test` but never
`*IT`, so Failsafe runs it. Two details are load-bearing and must not be "cleaned up":

- `useManifestOnlyJar=false` — `WorkerMain` reads `java.class.path` to fork itself, and a
  manifest-only jar hands the forked JVM a truncated classpath.
- The sidecar (and therefore all workflow state) lives in a container that **outlives the
  worker JVM**. The test SIGKILLs the worker while it is blocked inside a tool, restarts it,
  and asserts that the completed LLM activity is not re-run and the tool's side effect
  happens exactly once. Weakening either assertion removes the only end-to-end evidence
  that this library does what it claims.

## Static analysis fails the build

Checkstyle runs at `validate`, SpotBugs and PMD at `verify`, all with fail-on-violation, and
they are declared in the parent's `<build><plugins>` so **all four modules inherit them**.
Config lives at the reactor root: `checkstyle.xml`, `spotbugs-exclude.xml`, `pmd-rules.xml`.

- **Test sources are not analysed** (`includeTestSourceRoots=false` for Checkstyle,
  `includeTests=false` for SpotBugs and PMD). Only `src/main/java` has to satisfy them.
- Checkstyle: line length **120** (not the 100 the file's own header once claimed), no tabs,
  no trailing whitespace, newline at EOF, no star imports, no unused imports, braces at
  end of line, `HideUtilityClassConstructor`, `severity=error`. `SuppressionCommentFilter`
  is enabled, so `// CHECKSTYLE:OFF` / `// CHECKSTYLE:ON` is the escape hatch — use it
  instead of loosening the shared config.
- PMD rules that actually bite: `UnusedPrivateField` / `UnusedPrivateMethod` /
  `UnusedLocalVariable`, `AvoidReassigningParameters`, `FinalFieldCouldBeStatic`,
  `ReturnEmptyCollectionRatherThanNull` (never return `null` for a collection),
  `CloseResource`, and `EmptyCatchBlock` (a comment inside the block satisfies it).
- SpotBugs: `effort=Max`, `threshold=Medium`. `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` are excluded
  globally because records expose their components by design — defensive copying still
  belongs in compact constructors (`List.copyOf`, `Map.copyOf`).
- Build on **17 or 21** — those are the CI-proven pair. The repo's own caveat is that JDK 25
  upsets the analysis plugins; that one is inherited from the README, not verified here.

## Wiring: what actually makes a `ChatClient` durable

The most expensive thing to get wrong here. **Two independent mechanisms in the starter
attach the durable advisor, and they do not cover the same set of clients:**

1. `daprDurableChatClientCustomizer`, a `ChatClientCustomizer`. Spring AI applies
   customizers only to the **auto-configured `ChatClient.Builder` bean**, so a builder from
   the static `ChatClient.builder(chatModel)` never sees it. Adds the *generic* advisor at
   `LOWEST_PRECEDENCE - 1`, generic workflow name.
2. `DurableChatClientBeanPostProcessor`, a `BeanPostProcessor`. It fires on **every
   `ChatClient` bean, whatever builder produced it**, and re-builds it via
   `mutate().defaultAdvisors(...)` with a *per-agent* advisor at `LOWEST_PRECEDENCE - 2`
   (so it wins on the same client) and workflow name `spring-ai.<beanName>.workflow`.

So the precise rule is narrower than "always build from the managed builder": a statically
built client is silently non-durable **only when it is not a `ChatClient` bean** — a field
inside a `@Component`, built from an injected `ChatModel`. Expose it as a `@Bean` and the
post-processor rescues it. The clean options remain: inject the managed `ChatClient.Builder`
(`clone()` it per client), or declare each agent as its own `@Bean ChatClient`.

Second-order, and the one worth checking before you invent a bean shape: the per-agent
workflow names are registered on the worker at startup from
`context.getBeanNamesForType(ChatClient.class, false, false)` — i.e.
`includeNonSingletons=false, allowEagerInit=false`. The post-processor applies no such
filter. A `ChatClient` bean that is prototype- or otherwise-scoped, or whose type cannot be
resolved without eager init (a `@Bean` method declared to return a supertype, a
`FactoryBean`), therefore gets an advisor pointing at a workflow name nothing ever
registered. Keep `@Bean` methods declared as returning `ChatClient`, singleton-scoped.

Nothing in the test suite covers either mechanism end to end — there is no
`ApplicationContextRunner` test for `DiagridSpringAiAutoConfiguration` (the conversation
module has one; the starter does not). A green build proves nothing about durability
attachment, so changes here must be checked by running an example.

Two more consequences of the design:

- **The durable advisor is terminal.** It never calls `chain.nextCall()`, so any advisor
  ordered after it never runs. It logs a one-time WARN naming stranded advisors — order
  your own advisors *before* it.
- **Registry identity is a third, separate bean-only path.**
  `ChatClientAgentBeanPostProcessor` also only sees `ChatClient` beans, and the agent name
  *is* the bean name. A durable client that is not a bean has no registry identity.

## Durable-execution invariants

- Every call schedules a **fresh `UUID.randomUUID()`** instance: no dedup, no content
  hashing. Passing `DurableAdvisor.INSTANCE_ID_KEY` switches the call to `attachOrRun`, an
  attach handle for retry/recovery. Do not add a new dedup contract — these semantics are
  expected to move into the Dapr runtime.
- `diagrid.spring-ai.completion-timeout` is a **wait budget, not a cancel**: it throws
  `DurableCallTimeoutException` carrying the instance id while the workflow keeps running.
- Everything crossing the workflow boundary is **Jackson 2** (`jackson2.version`), because
  that is what durabletask's `DataConverter` uses — while Spring AI 2.0 uses Jackson 3
  under `tools.jackson`. Both are on the classpath; do not "unify" them. Records that cross
  the boundary must round-trip; `AgentRequestSerializationTest` is the guard.
- `AgentWorkflow` is orchestrator code and is **replayed**. The retry policy and
  `max-iterations` are captured once at startup precisely so they stay constant across
  replays — do not make them per-call. Keep non-determinism in activities.
- Tools: `@Tool` beans are rediscovered every boot and so survive a cold restart mid-call.
  Request-scoped `.defaultTools(new X())` callbacks are registered in memory at call time
  and are **not** recoverable after one. Execution resolves a tool by **bare name**,
  process-wide, last-write-wins — keep tool names app-unique and tools stateless.
- Chat-memory conflict detection is **string matching**: the repository raises
  `ConcurrentConversationModificationException` only when the Dapr SDK's error message
  contains `etag` (case-insensitively). A message change upstream silently degrades it to a
  raw `RuntimeException` — re-check it whenever you bump `dapr.version`.
- Only `.call()` is durable. `.stream()` is not, in any module.

## Catalyst, only what matters here

- Nothing runs beside the app; the sidecar is Catalyst's. The dev loop is
  `diagrid dev run -f <app>-dev.yaml` from the example's directory. `--app-id` is
  **deprecated** at v1.66.0 ("use `--id` instead") — write `-a/--id`.
- The project needs the managed workflow store: `diagrid project create <name>
  --enable-managed-workflow` (that flag does exist on `create`). `--enable-agent-infrastructure`
  does **not** exist on `create`, and on `project update` it is rejected for a cloud project
  with the managed KV store — it is for BYOC/private-region projects, or cloud projects
  without managed KV.
- **Do not assume the `agent-*` components exist.** Checked against five live projects in one
  org: three had all five `agent-*` plus `kvstore` and `pubsub`; one had four `agent-*` with
  **no `agent-registry`** and no `kvstore`; one had **no `agent-*` at all**, only `kvstore` and
  `pubsub`. So the defaults `diagrid.spring-ai.registry.statestore=agent-registry` and
  `…memory.statestore=agent-memory` are not safe — run `diagrid component list --project <p>`
  and point them at stores that exist. The registry store must use `keyPrefix: none`.
- `diagrid.spring-ai.registry.app-id` must equal the App ID the workload runs under; nothing
  can read it from inside the JVM. `diagrid workflow list|get` inspects runs by instance id,
  but only for projects on the managed workflow KV store. `--app-config` exists only on
  `diagrid appid update` (hidden from the top-level help, still the only command with the
  flag), not on `diagrid app update` — which is why the travel-planner Makefile uses it.
- `diagrid agent` and `diagrid managed-agent` are different resources; `managed-agent` is
  hidden and restricted to Diagrid employees, so keep it out of this repo entirely.
- A fuller platform primer lives in the `diagridio/catalyst-ai` plugin. That repo is private,
  so nothing above depends on it — everything here stands alone.

## Maven Central: three coordinate families, only one live

- **Live:** `io.diagrid:diagrid-spring-ai-{parent,core,starter,memory,conversation}` at
  **0.1.0 and 0.2.0**. `main` develops `0.3.0-SNAPSHOT`, which is published nowhere.
- **Dead, but resolvable** — never add either: `io.diagrid:diagrid-spring-ai-agent-registry`
  exists at **0.1.0 only** (merged into the starter at 0.2.0), and the entire pre-rename
  `io.diagrid.dapr:dapr-spring-ai-*` group exists at **0.1.0 only** (superseded by #39). A
  model that finds these and pins them gets a build that resolves and a library that is a
  release behind.
- Check versions with `https://repo1.maven.org/maven2/io/diagrid/<artifact>/maven-metadata.xml`.
  **Do not use `search.maven.org/solrsearch`** — its index returns zero results for this
  group and has already produced one wrong "the artifacts don't exist" conclusion.
- Publishing is two manual steps: `release.yml` uploads a **staged** deployment
  (`autoPublish=false`) that a human releases in the Central Portal, and `post-release.yml`
  then re-checks Central, tags, drafts the GitHub release and opens the version-bump PR. So
  a **draft GitHub release is not evidence** that the Maven artifacts are unpublished — and
  neither is an empty Central search. Check the metadata URL.

## Conventions

- Conventional commit subjects. **DCO sign-off is not required** and most commits do not have
  one (15 of 78 non-merge commits on `main`) — `git commit -s` is harmless but not the house
  style. What *is* enforced is that commits reaching `main` are **cryptographically signed**
  (`required_signatures` in the branch ruleset); the squash commit GitHub creates on merge
  satisfies that, so this only bites if you try to push to `main` directly.
- Two-space indent, 120 columns. Javadoc on public types carries the *design rationale* —
  why the durable advisor is terminal, why activity options are fixed at startup, why the tool
  registry is last-write-wins. It is the best documentation in the repo: update it, don't
  delete it. POM and workflow comments carry the same weight.
- Each auto-configuring module has a
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  A new auto-configuration class does nothing until it is listed there.
- Configuration properties are `@ConfigurationProperties` **records** with null-normalising
  compact constructors that apply the defaults. Follow that shape; don't reach for `@Value`.
- Dependency versions come from three BOMs imported in the parent — Spring Boot 4.0.5,
  Spring AI 2.0.0, Dapr 1.18.0. Never pin a version in a module POM. Dependabot watches
  `/diagrid-spring-ai` and the workflow actions daily and **ignores patch bumps**, so a patch
  upgrade is always a deliberate manual change.
- Every published artifact bundles `LICENSE.md` into `META-INF` (BUSL-1.1, Diagrid-modified).
  That execution runs in every build, not just `-Prelease`.
