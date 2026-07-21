# Contributing

Thanks for your interest in contributing to the Diagrid Java AI integrations.

## Prerequisites

- **JDK 17** — the library targets Java 17 and its static-analysis tooling is pinned to run on 17, so build with 17. (With [SDKMAN!](https://sdkman.io/): `sdk use java 17.0.11-tem`.)
- **JDK 21** — recommended for the example apps (they target Java 21) and as the app runtime (virtual threads).
- **Maven 3.9+** — no Maven wrapper is vendored; use your own `mvn`.
- **Docker** — only for the integration-test lane (a Dapr sidecar runs via Testcontainers).
- A local **[Ollama](https://ollama.ai/)** serving `llama3.1:8b` — only for the crash-recovery integration test.

## Project layout

The Maven reactor lives under [`dapr-spring-ai/`](dapr-spring-ai) with five published modules — `core`, `starter`, `agent-registry`, `memory`, `conversation` — plus standalone example apps under [`dapr-spring-ai/examples/`](dapr-spring-ai/examples) that are **not** part of the reactor.

## Build and test

Run everything from the reactor root:

```bash
cd dapr-spring-ai
mvn -B clean verify        # compile + unit tests + Checkstyle/SpotBugs/PMD
```

| What | Command |
|------|---------|
| Full build: unit tests + static analysis | `mvn -B clean verify` |
| Install to the local repo (so the examples resolve the snapshot) | `mvn -B clean install` |
| Compile an example app (JDK 21) | `mvn -f examples/travel-planner/pom.xml package -DskipTests` |

Unit tests run under Surefire; the `probe` and `integration` tagged tests are excluded from the default build.

## Integration tests

The crash-recovery integration test is heavy and opt-in — it needs **Docker** (a Dapr sidecar via Testcontainers) and a local **Ollama** serving `llama3.1:8b`:

```bash
mvn -pl dapr-spring-ai-core -Pintegration verify
```

Without Ollama the test skips itself. It is not part of the default build or of CI.

## Static analysis

Checkstyle runs at `validate`, SpotBugs and PMD at `verify`; their configuration lives at the reactor root (`checkstyle.xml`, `spotbugs-exclude.xml`, `pmd-rules.xml`). The build fails on violations, so keep it clean. Build on **JDK 17** — some static-analysis tooling misbehaves on newer JDKs.

## Pull requests

- Branch off `main` and open a PR — direct pushes to `main` are blocked.
- CI ([`build.yml`](.github/workflows/build.yml)) builds and runs the full unit-test + static-analysis suite on **JDK 17 and 21** for every PR; keep it green.
- Use [Conventional Commits](https://www.conventionalcommits.org/) for commit and PR titles (`feat:`, `fix:`, `docs:`, `chore:`, `ci:`, `build:`, `refactor:`, `test:`).
- No sign-off (DCO) is required.
