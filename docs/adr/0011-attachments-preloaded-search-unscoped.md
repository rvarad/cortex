# 0011. Attached files are pre-loaded, and `search_library` is never scoped to them

**Status:** Amended 2026-09-17
**Verified by:** Ported from `CHAT-AGENT-PLAN.md` §2 (F4), §4.4, §11.7. Checked
2026-10-01: `AgentService` loads attachments before the first model call;
`searchLibrary` takes no file ids; `AttachmentLoader` and `LibraryTools` pass `true`
and `false` to `SourceRefs.remember`.

## The question

How does the agent use attached files, and how is the user told when it goes beyond
them?

## Decision

Attached files are read into the prompt before the first model call, in order, until
the token budget is spent. They go in as their own user message, never into the
conversation. If they don't answer the question, the model calls `search_library`,
which searches the whole library. The step label for that search says so, and the
`attached` flag on each source is set by code from where the chunk came from.

## Why

"The user already decided those files are relevant; making the model ask for them
costs a round trip (4–6s) to re-decide something already decided."

"There is no narrow search that could silently widen, because there is no narrow
search at all." A `search_library` call "is by construction a visible second act."
"The model is never told about scope, so it has nothing to forget or misreport."

A file too big for the budget is reported with its file id: "A file silently missing
from the context is worse than one the model knows it cannot see."

## Alternatives rejected

- **Tiered soft scope** (the original F4): search the attached files first, then
  search unfiltered with a step event if that wasn't enough. "It was sound but solved
  a problem that pre-loading deletes."
- **Blended** (attached and library together) — still forbidden. The plan cites "the
  original reason" without restating it.

## What this costs

A request with attachments carries them whole, up to 128k tokens
(`chat.context.token-budget`).

## Invariants this creates

- The model is never told about scope. The broadening label comes from code.
- `attached` is set only through `SourceRefs`, from where the chunk came from.
- Attachment content belongs to this turn only; replay never carries it (ADR-0010).

## Amendments

### 2026-09-17 — Pre-loading replaced the tiered search (Brick 5)

The original mechanism was a scoped search first, then an unfiltered one. Brick 5
replaced it with pre-loading. The guarantee is unchanged: no silent widening.
