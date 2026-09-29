# Cortex

Java 21 / Spring Boot 3.5.11 / Spring AI 1.1.2. Five Maven modules plus a Next.js
frontend. `README.md` says what it does.

## Commands

```bash
mvn -B verify                          # what CI runs: build + tests, all modules
mvn -pl <module> -am test-compile      # after touching one module
mvn -pl <module> test                  # one module's tests
docker compose up -d postgres redis kafka  # local infra only
cd cortex-frontend && npm run dev | build | lint
```

`mvn compile` does not compile test sources. Use `test-compile` or `verify`.

## Things that will surprise you

- **`cortex-ingestion` owns the schema.** Every Flyway migration lives there, for
  every table, including ones only rag-orchestration reads. All three JPA services
  run `ddl-auto=validate`, so a missing migration breaks services you didn't touch.
  See `.claude/rules/db-schema.md`.
- **The root `pom.xml` aggregates but does not parent.** Each module parents to
  `spring-boot-starter-parent` directly, so versions are set per module, not
  inherited from the root.
- **CI runs on `master` and `production-hardening` and PRs to `master` only.** Pushes to a feature branch run
  nothing. Run `mvn -B verify` locally or open the PR.
- **ripgrep respects `.gitignore`, which excludes every `application-dev.properties`.**
  A search that finds no occurrence of a config key proves nothing. Open the file.

## How work happens here

- Explore, then plan, then code. Don't edit during exploration.
- **Decisions** live in `docs/adr/`, **facts learned by running things** in
  `docs/findings/`, **the feature in flight** in `docs/plans/`. Read the relevant
  ADRs before proposing an architecture change; several were expensive to reach.
  Before writing either kind, read its template: `docs/adr/0000-template.md`,
  `docs/findings/TEMPLATE.md`.
- Vocabulary is in `CONTEXT.md`. Use those words; don't drift to the synonyms it
  lists under `_Avoid_`.
- If a reader would think a line is a mistake, the comment saying why goes on that
  line, in the same edit. Not in a doc.
- **Instructions persuade, code enforces.** If something must hold — a budget, a
  scope, an ownership check — it goes in the code, not in a prompt or a comment.
- **When behaviour and code disagree, instrument.** Turn on a debug setting, log a
  stack trace, timestamp the stream. Reasoning from config files has produced about
  ten wrong theories in this repo and solved nothing.
- **One run is not evidence** for anything involving a model. Sampling varies. Run
  it five times before concluding the behaviour changed.
- **Before believing a measurement, know what it measures.** Postman renders SSE
  only on close; Chrome strips cookies from HAR exports; `mvn compile` skips test
  sources. Each has cost an afternoon here.
