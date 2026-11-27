# Chat agent

**Status:** Slice 1 is done: the agent searches, chains searches, cites real
chunks, refuses without evidence, broadens visibly past attached files, and its
step events, streamed answer and citation clicks all work in the browser (Bricks
1–6). Conversations are stored and a follow-up resolves against the turns before
it, and the Slice 2 frontend (sidebar, reload, delete) works in the browser (Bricks
7, 10). Live checks finished 2026-11-27: Bricks 8, 9 and loop hardening are verified,
and Bricks 6 and 10 were re-checked on the new `ChatPanel` layout. Two things did not
pass as written: Stop doesn't stop the agent (deferred), and the answer reaches the
browser in one burst, not segment by segment (upstream of our code; see findings
2026-11-26 and 2026-11-27). Next: the follow-ups under "Found in the live checks".
**Branch:** feature/chat
**Started:** 2026-09-01

## Goal

A user asks a question in chat and the agent finds the answer itself: it plans,
searches the library, reads files, searches again if the first look was thin, and
streams back a cited answer while showing a step event for each thing it does.
Chats are stored per user: each one survives a reload, only its owner can see it,
and a follow-up is understood in light of what was already said and done in that
chat.

## Not doing

- **The other four Definition-of-Done points.** Both slices clear only the
  happy-path subset: *feature* and *honest docs*, two of `ROADMAP.md`'s six. Graceful
  failure, idempotency, observability and failure-path tests are the hardening pass
  that follows. A deliberate deviation (source §1), not an oversight. Every DoD in
  this file is that subset.
- **Tools with side effects.** Every tool is read-only. Only the context-management
  half of Claude Code is in scope, not its action half.
- **Injection defense (G.5).** Safe to defer only because tools are read-only and
  same-user. It becomes a hard prerequisite when any of these happens: a tool with
  side effects is added; uploads open to strangers; the cost of a request stops being
  bounded.
- **Tool-call budget.** The round cap bounds rounds, not calls; one round can hold
  any number of calls. Deferred 2026-09-09 with a tripwire: build it when uploads open
  to strangers, when a tool costs materially more per call than a search, or when the
  logs show a real request over ~8 calls. Its shape is already decided: a per-request
  counter in `ToolContext`, budget ~8, past which the tool returns text instead of
  searching. Not a token budget. "Nothing new" is a partial guard against
  near-identical calls, not this budget.
- **Faithfulness validator** (grounding layer 3). Shares its judge with roadmap E.2;
  waits for a golden set to measure against.
- **Wiring the Spring AI per-call metrics** (AO.6 / Phase 1B) and framework-managed
  memory. The migration made them available; turning them on is the ops pass.
- **Compaction.** Removed, not deferred: the replay window can't grow and
  `recall_conversation` reaches everything outside it. Reopen only if paging through
  history proves too slow for the model.
- **`list_files(summaries)` tool.** Needs G.10 precomputed summaries first.
- **Rerank (G.4)** and **retrieval quality (R.1 overlap, R.2 seek precision).**
  Gated on the eval harness (E.1–E.3). Citation seeks are 60s-grained; watch it in
  the demo.
- **Speaker queries** ("what does Donny say?"). Unanswerable without diarization →
  a `speaker` column → metadata-filtered retrieval. A prompt line explaining this was
  drafted and left out 2026-09-24.
- **Splitting `AgentService`.** Asked for at Brick 5, deferred 2026-09-21. It has
  grown since and is still one class.
- **Step persistence.** Step events are live only; a reloaded answer has no step
  timeline. The tool calls behind them are stored.
- **Tool calls of failed turns.** A failed turn saves no answer row, so its calls
  aren't recorded. The log still has them.
- **"Load earlier" on reload.** Reload returns the newest 50 rows and the API has no
  cursor. Needs a `before` parameter on `GET /conversations/{id}`, then a control in
  `ChatPanel`.
- **Search over history, row-level security, an FK on `file_metadata.user_id`,
  retiring `ChatService`.** Later.
- **Unit tests for the conversation layer.** None this slice (decided 2026-09-25).

## Decisions

- **How much of the Definition of Done does a slice clear?** — Feature and honest
  docs only; the rest is the hardening pass. Stated in source §1 so a partial DoD
  isn't read as a complete one.
- **Who runs the agent loop, and who stops it?** — see ADR-0009
- **What does the model see of earlier turns?** — see ADR-0010
- **How are attached files used, and how is widening shown?** — see ADR-0011
- **Where and how are conversations stored?** — see ADR-0012
- **What does the browser see while the agent works?** — see ADR-0013
- **When does chat send sources?** — see ADR-0008
- **Which service holds the loop?** — `cortex-rag-orchestration`. Next to its
  tools; no new service.
- **How are answers grounded?** — Citation integrity in code (`sanitizeCites`,
  `responseSchema`) plus refusal in the prompt. The validator is deferred.
- **How do citations stay correct across several searches?** — Chunk ids inside the
  loop, renumbered 1…N at the edge by `CitationNumberer`. Per-search numbering
  restarts at 1, so two chunks would both be "1".
- **How much history does each reader load?** — Replay the newest 8 rows (plus one
  probe row), recall 20 per page, reload the newest 50. Rows, not turns, so nothing
  counts a whole conversation.
- **What happens at the round cap?** — One more call with no tools declared, so the
  model has to answer from what it has. Spring AI 1.1.2 can't set Gemini's
  function-calling mode.
- **What gets saved when an answer doesn't finish?** — Nothing. The question stays,
  unanswered; replay marks it. An empty answer row broke every later question.
- **How does a chat start?** — Two calls: `POST /conversations`, then
  `POST …/messages`. Opening `/chat` creates nothing.
- **What's the title?** — The first question, trimmed to 80 chars. No model call.
- **What does a citation tooltip show?** — `filename · time`. A `snippet` field was
  rejected 2026-09-21.
- **Temperature?** — 0.3. Lowering it changes how often the model makes its likeliest
  choice, not which choice that is.

## Open questions

- **Who holds the answer back: Vertex, or the google-genai SDK / Spring AI?** The
  chunks reach `streamRound` bunched (finding 2026-11-27). Needs a direct
  `streamGenerateContent` call. Goes with the model-change work.

## Found in the live checks (2026-11-26/27)

Later merges changed chat code after the 2026-10-01 browser check: reliability
hardening (2026-10-27) added ADR-0014's tests, which now cover part of the failure
path, and failure-boundaries brick 15 changed the stop path. Follow-ups, none blocking:

- **Stop.** Deferred; to be built so it really stops the agent (finding 2026-11-26).
- **Identical `recall_conversation` calls aren't caught.** "Nothing new" lives in
  `LibraryTools` only. One run called `recall_conversation(page=0)` five times with
  the same result and spent the whole round cap on it; another, three times.
- **`answerAtCap` skips `warnOnDropped`.** A dropped cite at the cap isn't logged.
- **The model puts the wrong thing in `cites`:** a fileId (twice, both when no chunk
  was fetched in that request) and a start time (`"180.0"`, once). `sanitizeCites`
  drops them, so nothing wrong is shown, but the segment ends up uncited.
- **One empty answer** (about 1 in 25 runs): three tool rounds, then no segments, so
  nothing saved. The raw text wasn't logged then; not seen again.
- **Model behaviour on `gemini-2.5-flash`:** near-duplicate searches, recall called
  when the turn is visible, "?" answered with a generic "I can help you…". Input for a
  measured model comparison, not a reason to switch on one run.

## Slices

### Slice 1 — the bounded agent (single-turn)

**When this slice is done:** A single question gets a planned, cited, streamed
answer from a real agent loop, with no persistence: reload loses it. This is the
slice you demo.

**DoD scope: happy-path subset.** The brick DoDs below clear *feature* and *honest
docs* only. The other four Definition-of-Done points are the hardening pass.

#### 1. One tool call, no loop — ✅ DONE 2026-09-07

Declare `search_library`, send it, parse the function call, run the search, feed
the result back, get a text answer. Proves the genai function-calling round trip,
the one real unknown.

**DoD:**
- [x] Logs show question → function call → search runs → text answer. No UI.

#### 1.5. Spring AI migration — ✅ DONE 2026-09-09

Add `spring-ai-starter-model-google-genai`; `search_library` becomes a `@Tool`;
`userId` moves to `toolContext`; delete the hand-rolled `agent` package. The starter
changes bean creation order: see the 2026-09-09 finding in `docs/findings/`.

**DoD:**
- [x] Same question → same tool call → same answer as Brick 1.

#### 2. Loop and round cap — ✅ DONE 2026-09-09

Single round trip becomes a loop that ends on an answer or at `MAX_ITERATIONS` (5).
Adds `read_file`.

**DoD:**
- [x] At least one tool call, then an answer.
- [x] The cap is enforced, forced at `MAX_ITERATIONS = 0` (`1` doesn't exercise it).

#### 3. Grounding — ✅ DONE 2026-09-12

Final answer as segments via `responseSchema`; citations are chunk ids in the loop,
validated against what the tools returned, renumbered at the edge.

**DoD:**
- [x] A question with no attachments produces a cited answer via search; each
      citation resolves to a real chunk (checked at chunk level on a two-file
      question).
- [x] A no-evidence question refuses rather than inventing.

#### 4. Streaming — ✅ DONE 2026-09-14

`answerStream` sends a step event per tool call, streams `source` and `segment`
over SSE, ends with `done`.

**DoD:**
- [x] `curl -N`, timestamped per line: step → (4s) → step → (4s) → segments → done.
      Every source arrives before the segment that cites it.

#### 5. Attachments and scope — ✅ DONE 2026-09-17

Attached files pre-loaded before the first model call; `search_library` stays
unscoped; the step label and the `attached` flag come from code.

**DoD:**
- [x] A question with a small attached file is answered from it, cited, with
      `attached: true` and no search step.
- [x] A question whose answer isn't in the attached files broadens visibly and marks
      the source discovered (`attached: false`).

#### 6. Frontend — ✅ DONE 2026-09-18 (`5590fa4`)

`ChatPanel` renders the step row; attached files pass `fileIds`; the sources list
marks attached vs discovered.

**DoD:**
- [x] In the browser, step events render live, the answer streams, and citations
      click through to timestamps. Browser check confirmed 2026-10-01. Re-checked
      2026-11-27 on the new layout: step events and citations pass; the answer
      arrives in one burst (finding 2026-11-27).

### Slice 2 — multi-turn (persistence and memory)

**When this slice is done:** Every user's chats are stored and visible only to their
owner; a chat survives a reload with working citations; a follow-up is answered in
light of the earlier turns and what the agent did in them, and turns that fell out
of the replay window are reachable through `recall_conversation`.

**DoD scope: happy-path subset.** As for Slice 1: *feature* and *honest docs* only.
The other four Definition-of-Done points are the hardening pass.

#### 7. Schema and entities — ✅ DONE 2026-09-22

V10 (`conversation`, `conversation_message`) in `cortex-ingestion`; `Conversation`,
`ConversationMessage`, repositories.

**DoD:**
- [x] Services boot against it with `ddl-auto=validate`.

#### 8. Write path, replay, endpoints, gateway route — ✅ DONE 2026-11-27

`ConversationService.ask`, `ConversationHistory` replay, the five
`/conversations` endpoints, gateway route to `RAG_HOST:8083`. Reshaped in testing
through 2026-09-25: row limits, `answerStream` returns whether it finished, V11
`tool_calls`, unanswered questions replayed with a marker, prompt rewritten, step
labels reduced.

**DoD:**
- [x] A follow-up resolves against the prior turn. 2026-09-24: "wdym?" and "so does
      dony do anything?" answered from history with no tool calls.
- [x] Another user's conversation id → 404 on every endpoint. 2026-11-27: GET,
      POST …/messages and DELETE all 404 with a second account's session; the chat
      kept all 32 rows, and the second account's list was empty.
- [x] An unfinished run → no answer row in `conversation_message`. 2026-11-26: checked
      by killing rag-orchestration during the tool round, not with Stop, which doesn't
      stop the agent (finding 2026-11-26).
- [x] A long answer doesn't truncate silently, checked through
      `POST /conversations/{id}/messages`. 2026-11-27: 23,226 chars, `finishReason`
      STOP, 28 segments saved, the last one a whole sentence.
- [x] A question after an unanswered one sees the "No answer was given" marker in
      replay, seen in a live run (2026-11-26).
- [x] The attachment block's closing line ("End of attached files…") seen in a
      live run (2026-11-26).

#### 9. Recall tool — ✅ DONE 2026-11-27

`recall_conversation(page)`: 20 rows a page from just past the replay window,
rendered as text with no citable chunk ids. Gained `Attached:` and `Tools used:`
lines 2026-09-24.

**DoD:**
- [x] In a chat longer than the replay window, a reference to an early turn makes
      the model call `recall_conversation` and answer correctly, citing a chunk
      fetched in this request. 2026-11-27: "In my first question you summarized a
      video. Where in that video is each pattern introduced?" → one recall, then a
      search and `read_file`, three segments each citing a chunk from that request.
      Recall was called in four other runs too; see "Found in the live checks" for
      the repeated-call problem.

#### Loop hardening (unnumbered; came out of testing) — ✅ DONE 2026-11-27

`AgentService.answerAtCap`: at the round cap, one more call with no tools. "Nothing
new": a tool whose results were all already seen this request returns one sentence
instead of the chunks.

**DoD:**
- [x] A question that hits the cap gets an answer from the final no-tools call, and
      that answer is saved. 2026-11-26, at the real cap of 5: "hit the 5-iteration
      cap" → "answered at the cap" → row saved. The open point is answered: yes,
      Gemini accepts function calls in the history when none are declared (that
      history held five).
- [x] "Nothing new" fires in a live run on a repeated search or a re-read file.
      Seen 2026-11-26/27 on both, several times.

#### 10. Frontend — ✅ DONE 2026-09-25

`/chat` and `/chat/[conversationId]`, `/chat?attach=`, the `/files/[id]/chat`
redirect, `replaceState` on first send, `ConversationSidebar` and
`ConversationsProvider`, reload via `toMessages`, the collapsed step row. Known
mismatch: an empty answer row from before 2026-09-23 replays as unanswered but
reloads as an empty bubble.

**DoD:**
- [x] Reload the page → the conversation is still there, citations still click
      through.
- [x] The sidebar lists, reopens and deletes conversations; a fresh `/chat` creates
      nothing until the first send.
- [x] After stopping mid-answer, reload shows "No answer was saved".

Browser check confirmed 2026-10-01. Re-checked 2026-11-27 on the new `ChatPanel`
layout: sidebar, reopen, delete (rows gone, none orphaned), reload with working
citations, and a fresh `/chat` creating nothing. "No answer was saved" was re-checked
on a killed run (2026-11-26), since Stop no longer ends one.

#### 11. Docs — ✅ DONE 2026-09-25

Source plan updated to describe the code as built; its decisions since graduated to
ADR-0009 to ADR-0013.

**DoD:**
- [x] Every Slice 2 decision changed in testing is stated with its reason.

## Done when

The happy-path subset only: this checks *feature* and *honest docs*, not the
hardening-pass points.

1. Ask a question with nothing attached: step events show, a cited answer streams,
   and a citation click seeks the player to its timestamp.
2. Attach a file and ask something it answers: no search step, sources marked
   attached. Ask something it doesn't: the "Not in your attached files" step, sources
   marked discovered.
3. Ask something nothing in the library covers: the agent refuses.
4. Ask a follow-up ("wdym?"): answered from the conversation, no tool calls.
5. Reload: the chat is there with working citations; the sidebar lists, reopens and
   deletes it.
6. Grow a chat past 8 rows and refer to its first turn: `recall_conversation` is
   called and the answer cites a chunk fetched in that request.
7. Ask a question that hits the round cap: an answer still arrives and is saved.
8. Request another user's conversation id on every endpoint: 404 each time.
9. Stop an answer mid-stream and reload: "No answer was saved". (Stop is deferred;
   checked 2026-11-26 on a killed run instead.)
