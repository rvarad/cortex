# 0012. Every user's conversations live in one relational table, as jsonb rows

**Status:** Accepted, 2026-09-21
**Verified by:** Ported from `CHAT-AGENT-PLAN.md` §5.1 and §5.2. Checked 2026-10-01:
V10 creates `conversation` and `conversation_message` with `content jsonb` and an
index on `(conversation_id, created_at)`; every message read goes through
`ConversationMessageRepository.findLatest`; `ConversationService.requireOwned` looks
conversations up by id and user.

## The question

Where are chats stored, and in what shape, when reload, replay and recall each need
a different view of them?

## Decision

One `conversation_message` table for all users, one row per message, in the existing
Postgres. `content` (jsonb) holds what was said: user `{text}`, assistant
`{segments, sources}`. `tool_calls` holds what was done. The display, replay and
recall views are built from these rows at request time, not stored.

## Why

"A conversation *is* read whole, which makes 'one document per chat' feel natural. It
loses anyway: every new message rewrites the whole document; recall reads *some* old
turns, which is a `WHERE` on rows and a load-and-slice on a document; and it would
mean a second database for one feature. `jsonb` gives the document part in a
relational row." Same pattern as `pipeline_events.metadata`.

"`media_chunk` already holds every chunk of every user. With an index on
`(conversation_id, created_at)`, loading a chat touches only that chat's rows.
Isolation is the index and the `WHERE`." Row-level security is the stronger lever if
ever wanted, "and it works *because* it is one table."

Answers are stored as segments, not prose: flattening to prose kills clickable
citations. The replay view is derived, not stored, "so the two cannot drift."

## Alternatives rejected

- **A document per conversation** — rewrites the whole document per message, slices
  in memory for recall, and adds a second database.
- **A separate plain-text replay store** (the 09-01 sketch) — a second copy that can
  drift from the display rows.

## What this costs

Isolation rests on every query filtering by user. There is no row-level security yet.
Message rows carry no `user_id`; ownership is checked on the conversation first.

## Invariants this creates

- Every conversation lookup is by id and user. Anything else is 404, never 403.
- Message rows are only read through `findLatest`, with a limit. Nothing loads a
  whole conversation.
- Assistant `content` is never flattened to prose.
- What was said (`content`) and what was done (`tool_calls`) stay separate columns.
