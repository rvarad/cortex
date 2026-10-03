# 0009. Our code drives the agent loop on Spring AI's `ToolCallingManager`, and owns the round limit

**Status:** Amended 2026-09-07
**Verified by:** Ported from `CHAT-AGENT-PLAN.md` §2 (F1), §4.1, §9.1. Checked
2026-10-01: `AgentService` sets `internalToolExecutionEnabled(false)`, calls
`executeToolCalls` in both loops, and holds `MAX_ITERATIONS = 5`.

## The question

Who runs the agent loop (ask the model, run the tools it asks for, ask again), and
who stops it?

## Decision

Spring AI makes the model calls and `ToolCallingManager` executes the tools, but the
framework's own tool execution is switched off. Every response carrying tool calls
comes back to `AgentService`, which runs them, appends the results and calls again.
`AgentService` stops the loop after `MAX_ITERATIONS` rounds.

## Why

"You run every arrow. That is what makes per-call cost/latency measurable and tool
steps observable." Step events (ADR-0013) and visible broadening (ADR-0011) both need
to see each tool call before it runs. With internal execution off, "the
`ChatResponse` carrying the tool calls flows straight out to us."

**Why the round limit is ours.** "The framework's own loop is recursive and uncapped
— `internalStream` calls itself on every tool result, with nothing bounding depth.
`maxIterations` stays ours to write in either world. Another reason to take the loop
rather than let it run." What the cap really bounds is context: each search adds
about 2,800 tokens, "and it accumulates — every tool result stays in the transcript."

## Alternatives rejected

- **Let Spring AI run the loop** — uncapped recursion, and tool calls never reach
  our code, so there is nothing to emit a step from.
- **Hand-rolled loop on the raw genai `Client`** (the original F1) — the dispatcher,
  schema generator and `toolContext` equivalent "would each be hand-written and then
  deleted."

## What this costs

The cap counts rounds, not calls; one round can hold any number of calls. A
per-request call budget is deferred (plan §8.9).

## Invariants this creates

- Every options object sets `internalToolExecutionEnabled(false)`. Turning it on
  hands the loop to the uncapped framework recursion.
- Both loops check `MAX_ITERATIONS` before executing a round's tools.

## Amendments

### 2026-09-07 — From a manual genai loop to Spring AI, before Brick 2

The 2026-09-01 choice was "Manual now, Spring AI migration at the ops pass." The
Spring AI 1.1.2 sources jars showed `ToolCallingManager` gives the loop back — "the
gate most likely to kill it, and it passed." Migrated 2026-09-09.
