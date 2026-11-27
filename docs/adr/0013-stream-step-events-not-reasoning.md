# 0013. Chat streams step events and the answer, never the model's reasoning

**Status:** Accepted, 2026-09-01
**Verified by:** Ported from `CHAT-AGENT-PLAN.md` §2 (F2), §4.3, §5.6, §5.7, §11.2 and
§11.4. Checked 2026-10-01: `SseEvents.send` maps `StepEvent` to `step`,
`SourceEvent` to `source`, `SegmentEvent` to `segment`, and drops `ToolCallEvent`;
`SseEvents.done` sends `done`; both agent endpoints use `SseEmitter(180_000L)`. The
plan's table names the events `step` / `answer` / `done`; the code splits `answer`
into `source` and `segment`.

## The question

What does the browser see while the agent is working, before the answer exists?

## Decision

A small typed menu over the existing SSE stream: `step` (a label), `source`
(ADR-0008), `segment`, and `done`. Step labels are chosen by code from the tool
called: reading attachments, searching (with the ADR-0011 wording when files are
attached), reading a file. Any other tool sends no step, and the frontend shows
"Thinking…". The model's intermediate reasoning is never streamed. Tool calls are
recorded for replay (ADR-0010) but never sent.

## Why

"Do **not** stream intermediate model reasoning tokens — the `step` pings carry the
'it's an agent' signal without the mess."

"Latency is dominated by thinking tokens", second-call latency ran 1.3s to 7.7s, and
that is "a concrete argument for F2's `step` events: something has to be on screen
during that." Verified at Brick 4: three distinct pauses of 4–6s, each now covered by
a step.

`done` is explicit "so a client can tell 'finished' from 'connection dropped'."

## Alternatives rejected

- **Streaming the model's reasoning tokens** — "the mess" the steps avoid.
- **One step per round** — "would show a single 'Searching library…' for two
  searches." Steps are emitted per call.

## What this costs

Recall sends no step. After a search, the spinner stays on "Searching library…"
during a recall, since nothing marks the search as finished. Steps are live only:
a reloaded answer has no step timeline.

## Invariants this creates

- `ToolCallEvent` never goes on the wire. The user sees steps, not calls.
- A step label comes from code, never from model output.
- Every finished stream ends with `done`.
- Both agent endpoints send events through `SseEvents`, so their streams are
  byte-identical.
