# 0010. Replay earlier turns with the tool calls made, never their results

**Status:** Amended 2026-09-24
**Verified by:** Ported from `CHAT-AGENT-PLAN.md` §2 (F3), §5.1, §5.3. Checked
2026-10-01: `ConversationHistory.messages` replays each stored round as real calls
plus `RESULT_NOT_KEPT`; V11 adds `tool_calls`, which stores no results.

## The question

When the model answers a follow-up, what does it see of the earlier turns?

## Decision

Each earlier turn is replayed the way it happened: the question (with the names of
any attached files), then each round of tool calls as real function calls with one
placeholder result per call, then the answer text. Results are never stored or
replayed. If one is needed again, the model calls the tool again. Attachment content
is never replayed.

## Why

Results stay out: if a turn needs chunks again, "it re-searches, fresh (cheaper +
more relevant; stale chunks otherwise mislead)."

Calls go in: "Testing showed the model could not explain its own earlier answers: it
saw the words it said, never what it did, so *'why did you say that?'* sent it
searching the library five times for the reason."

As real calls, not text: "A text block like 'Tool calls you made: …' in the model's
own past replies is a pattern it copies: into its answer, or, worse, *in place of* a
real call. Real calls can't be imitated as text: the only way to produce one is to
make one."
## Alternatives rejected

- **Questions and answers only** (the original F3) — the five-searches failure above.
- **A text summary of the calls** — a pattern the model copies instead of calling.

## What this costs

A follow-up that needs old evidence pays for a fresh tool call. A turn that fails has
no answer row, so its calls aren't recorded either.

## Invariants this creates

- `tool_calls` holds name, arguments and round, never a result.
- Every replayed call gets exactly one placeholder response.
- `RESULT_NOT_KEPT` stays JSON: Spring AI's Gemini adapter parses a tool response as
  a JSON object.
- An empty answer is never replayed. Gemini rejects a model turn with no parts.

## Amendments

### 2026-09-24 — Tool calls are stored and replayed

The 2026-09-01 choice was "Q&A pairs only (not tool transcripts)". Real
conversations showed the model could not explain its own earlier answers (§11.9).
V11 added the column. Results are still never replayed.
