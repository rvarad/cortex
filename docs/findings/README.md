# Findings

Things that turned out to be true about the world — usually someone else's world:
ffmpeg, Gemini, Spring, Postman, Chrome, ripgrep — that documentation didn't say
and that cost time to discover.

A **finding** is a fact. An **ADR** is a choice. A finding can only be superseded
by a better observation; a decision can be reversed.

Append-only, newest at the bottom. Use `TEMPLATE.md`. Split this file by area only
when it gets genuinely unwieldy — past four or five hundred lines. Premature filing
structure is friction, and friction means findings stop getting written.

**A finding you need loaded every session isn't a finding any more, it's a rule.**
Those graduate to `.claude/rules/` or to a comment on the line they're about. This
file is the archive you read when you're back in that area, or when a symptom
recurs.

---

## 2026-08-15 — Vertex/Gemini vision decodes HEVC-in-MP4 chunks

**Area:** gemini
**Cost:** Shaped a design. The first playback plan (2026-07-14) made every HEVC
upload finish a full transcode before chunking, only to protect Vertex from HEVC.

**What was seen.** Chunking an HEVC original with `-c copy` produces HEVC chunks.
The 2026-07-14 plan assumed Vertex "may not decode" them, "silently breaking vision
on exactly the files Apple users upload."

**What was actually happening.** Vertex decodes them. An HEVC test chunk came back
with a coherent `visual_summary`.

**How it was found.** Made an HEVC test file with ffmpeg (`libx265 -tag:v hvc1`),
pushed it through the pipeline, and read the `visual_summary` rows it produced.
Recorded as one test file; the source doesn't say it was repeated.

**Wrong theories.** "Vertex may not decode HEVC chunks" — an assumption, never
tested until this run.

**What changed.** Normalization moved off the critical path and runs in parallel
with chunking, which reads the original whatever the codec: ADR-0007. Still in
place as of 2026-09-30 — `MediaProcessingService.startFFmpegChunkingProcess` is
given the original's signed URL. If vision on an HEVC file ever returns an empty or
incoherent summary, re-test this first.

## 2026-04-24 — A dispatcher thread that stops on a shared flag gets revived by the next job (concurrency bug)

**Area:** spring
**Cost:** Corrupt data. One file's chunks were uploaded into another file's GCS
folder and saved in `media_chunk` under the wrong `fileId`. Fixed four days after
the report, in `fea2188` (2026-04-28).

**What was seen.** Chunks from one video in another video's `chunks/` folder in
the bucket.

**What was actually happening.** `MediaProcessingService` is a singleton, and the
job state (upload queue, chunk maps, `isRunning`) lived in fields on it. Jobs ran
one at a time on the Kafka listener thread, so no two jobs overlapped. The upload
dispatcher looped while `isRunning` was true or the queue was non-empty, waiting up
to one second per poll, with the job's `objectName` and `fileId` fixed as
parameters when the thread started. When job A finished it set `isRunning` to
false, but dispatcher A was still inside its one-second poll. If the next file's
event was already waiting, `processMedia` for B set `isRunning` back to true on its
first line, before dispatcher A woke. Dispatcher A saw true and kept going, now
pulling B's chunks off the shared queue and uploading them under A's name. It never
stopped, so every back-to-back upload added another stale dispatcher.

**How it was found.** Looked in the GCS bucket and saw the wrong chunks in a
file's folder. The mechanism above comes from reading the code as it was before the
fix (`git show fea2188^`), not from rerunning it.

**Wrong theories.** The original bug report blamed two jobs running at the same
time, with the second job's `clear()` wiping the first. With the config in git at
the time that couldn't happen: `MediaEventConsumer` called `processMedia` directly
on the single listener thread, with no executor and no listener concurrency set.
The shared state was the cause; the overlap was not the trigger.

**What changed.** `fea2188` moved all job state into `MediaProcessingContext`, one
per job, and the watcher and dispatcher loops now read that job's own `isRunning`.
A new job can't revive an old job's threads. Still in place as of 2026-09-30.
The same commit added `mediaProcessingExecutor` (2 threads), so jobs now do run at
the same time, which makes the per-job state load-bearing. One piece was not moved:
`uploadSlots` is still a field on the service, and cleanup uses it to wait for
"all uploads", which includes other jobs' uploads. Tracked in
`docs/plans/per-job-upload-wait.md`.

**The general lesson.** A stop flag has to belong to the thing it stops. If a
thread's exit condition reads state the next job also writes, the next job can
cancel the stop.

## 2026-09-09 — Spring AI's google-genai chat starter builds our Vertex `Client` before `@Value` placeholders are resolved

**Area:** spring
**Cost:** Every Vertex call in rag-orchestration failed: the new agent and the
existing chat path alike. Found during the Spring AI migration (Brick 1.5).

**What was seen.** After adding `spring-ai-starter-model-google-genai`, every Vertex
call failed with `UnknownHostException: ${spring.cloud.gcp.location}-aiplatform.googleapis.com`.
The `@Value` placeholders in `GeminiConfig` had been injected as literal text.

**What was actually happening.** Spring AI 1.1.2 guards its cached-content bean with
`CachedContentServiceCondition`, which calls `getBean()` while the condition is being
evaluated. That evaluation runs inside `ConfigurationClassPostProcessor`, a
`BeanFactoryPostProcessor`. Resolving it builds `GoogleGenAiChatModel`, which needs
the `Client`, which builds `GeminiConfig.genAiClient` — all before
`PropertySourcesPlaceholderConfigurer` (also a BFPP) has run, so no `${...}` can be
resolved. Spring says so a line later: "Cannot enhance @Configuration bean
definition 'geminiConfig' since its singleton instance has been created too early."
A `Condition` calling `getBean()` is an upstream anti-pattern. Nothing in our code was
wrong: `GeminiConfig` was byte-identical to the version that worked on 2026-09-07.

**How it was found.** A stack trace taken inside the bean method, which showed
`CachedContentServiceCondition.getMatchOutcome` → `ConditionEvaluator.shouldSkip` →
`ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry` →
`PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors`. The old chat
path breaking the same way proved it predated the agent work.

**What changed.** `GeminiConfig.genAiClient` reads its config from `Environment`
instead of `@Value`, with a null check that names the missing properties.
`Environment` is populated before any bean exists, so bean ordering can't catch it
out. Still in place as of 2026-10-01, with a comment on the line saying why. Don't
generalise it: `@Value` stays the default everywhere else, and only a bean known to be
dragged into the post-processor phase needs `Environment`. Don't revert it when
upstream fixes the condition either: the `@Value` version "worked by luck of
classpath ordering, not by design."

**The general lesson.** An unchecked bad value travels. The literal `${...}` went
through the region, into a URL, into DNS — four layers — before anything complained,
and the error looked like a networking problem. A required value that is absent must
stop things immediately. And a shared bean fails widely: "Does the old path break the
same way?" is the cheapest possible bisect.

## 2026-11-22 — Hibernate 6.6 applies `@SQLRestriction` to `findById` and to JPQL bulk updates, not to native SQL

**Area:** spring
**Cost:** Shaped a design (ADR-0015). Nothing broke.

**What was seen.** With `@SQLRestriction("file_status <> 'FOR_DELETION'")` on
`FileMetadata`, a `FOR_DELETION` row was not found by `findById`, by
`findByIdForUpdate` (a JPQL select with a lock), or by the derived queries
(`findByIdAndUserId`, `findByObjectName`, `findAllByUserId`). The JPQL bulk updates
(`rename` and the pipeline's status writes) changed 0 rows. The SQL that Hibernate
logged for a bulk update carried the condition: `... where fm1_0.id=? and
fm1_0.file_status='FOR_CLEANUP' and (fm1_0.file_status <> 'FOR_DELETION')`. Native
queries still found the row.

**What was actually happening.** Hibernate 6.6 adds the restriction to every SQL
statement it builds for the entity: `em.find`, derived queries, JPQL selects and JPQL
bulk updates. It leaves native SQL alone.

**How it was found.** `FileMetadataRepositoryTest` against Postgres (Testcontainers):
`aForDeletionRow_isHiddenFromEveryRead` and `aForDeletionRow_ignoresThePipelinesWrites`.
The SQL came from the test output.

**What changed.** ADR-0015 relies on it: one annotation hides a deleted file from every
JPA read and keeps the pipeline's JPQL writes off it. The sweep and search use native
SQL, so they still see the rows. `SearchService.getFileContext` reads chunks from
`media_chunk` by file id, which the restriction on `FileMetadata` cannot reach, so it
reads the file first.

**The general lesson.** A restriction on an entity hides that entity's rows. A query on
another table that holds its id still sees them.

## 2026-11-22 — EventSource gives its error handler no HTTP status, so a 404 looks like a lost connection

**Area:** browser
**Cost:** The page of a deleted file said the connection was lost, not that the file
was gone.

**What was seen.** A file's page was open while the file was set to `FOR_DELETION` in
psql. After a reload, the page showed the lost-connection line, and ingestion's log
showed a 404 for the stream's request (`/api/v1/files/<id>/events`).

**What was actually happening.** The browser's `EventSource` fires `error` with a plain
`Event`: no status and no body. A 404 on the open reaches the same handler as a dropped
connection, and `subscribeToPipelineEvents` passes every error on the same way.

**How it was found.** By hand in dev: the page, next to ingestion's log, after the
reload.

**What changed.** When the stream fails before any event arrives, the file page fetches
the file list. If the file is not in it, the page shows "This file no longer exists"
(`cortex-frontend/src/app/files/[fileId]/page.tsx`, with a comment on the line). Only
the open can answer 404, so a failure after an event is still a lost connection.

## 2026-11-23 — `created_at` holds the JVM's local time, but Postgres's `now()` is UTC

**Area:** postgres
**Cost:** SQL that compares `created_at` with `now()` is 5½ hours off on this machine.
The sweep avoids it. A check by hand in psql does not.

**What was seen.** In one psql session, during a manual check of the sweep's rule 1, a
file's newest event had `created_at` 14:57, and `now() AT TIME ZONE 'UTC'` read 09:32,
minutes after that event. On 2026-11-25, `SHOW TimeZone` gave `Etc/UTC`, and `now()`
read 11:06 while the machine's clock read 16:36 IST.

**What was actually happening.** `FileMetadata` and `PipelineEvent` set `created_at` in
`@PrePersist` with `LocalDateTime.now()`: the JVM's default zone, IST on this machine.
The columns are `timestamp without time zone`, so the zone is not stored. The database
session's zone is UTC, so `now()`, compared with these columns, is 5½ hours behind
them.

**How it was found.** psql queries that printed `created_at` next to
`now() AT TIME ZONE 'UTC'`, then `SHOW TimeZone`.

**What changed.** Nothing in code. The sweep already calculated its cut-offs in Java,
from the clock that writes the columns, and passes them to its queries (`Sweep.run`,
ADR-0016). For a check by hand, compare with `now() AT TIME ZONE 'Asia/Kolkata'`. Not
checked: the JVM's zone inside the containers. The columns hold whatever zone the
writing JVM has.

**The general lesson.** A `timestamp without time zone` column holds the writer's
wall-clock time. Compare it with the writer's clock, not with the database's.

## 2026-11-26 — Stop in the browser doesn't stop the agent; the answer is still saved

**Area:** spring
**Cost:** The 2026-10-01 browser check of Stop no longer holds: a stopped answer is
saved as if it had finished.

**What was seen.** A question stopped mid-answer in the browser at 21:21 has a saved
ASSISTANT row 14 s later.

**What was actually happening.** Stop aborts the browser's `fetch`. The server learns
of it only when `SseEvents.send` throws, and that throw is what unwinds the agent.
Nothing is sent during a tool round or while the model thinks, and a write into a
socket the client has just closed usually succeeds (the OS accepts the bytes; the
reset comes later). So the run finishes and `ConversationService.ask` saves it. The
socket part is the likely mechanism, not instrumented.

**How it was found.** The user's report, then the `conversation_message` rows for the
stopped question.

**What changed.** Nothing in code; Stop is deferred and will be built properly. The
"nothing is saved for an unfinished run" rule was checked instead by killing
rag-orchestration during the tool round: the question row stays, no answer row, and
reload shows "No answer was saved".

## 2026-11-27 — A chat answer reaches `streamRound` in one burst, after a long silence

**Area:** gemini
**Cost:** The browser shows the whole answer at once, after up to a minute with only a
step label. Looked like a frontend or gateway buffer; it is neither.

**What was seen.** In the browser, the answer appeared in one block, not segment by
segment. `curl -N` through the gateway (`:8080`), timestamped per receive with
`--trace-time`: the `step` event arrived at once, then 4.3 s of nothing, then every
`source` and `segment` within 5 ms, then `done`.

**What was actually happening.** The chunks of the answer round reach `streamRound`
already bunched. A 3190-char answer: no chunk for 7775 ms after the call, then all 15
within 32 ms, 4–6 of them in the same millisecond. A 23,226-char answer: 43.4 s of
nothing, then 96 chunks within about 95 ms. That much text can't be generated in that
time, so it was held before `chatModel.stream(...)` handed it over: by Gemini/Vertex,
or by the google-genai SDK or Spring AI. Which one is not known. Everything after
`streamRound` passes each chunk on in the millisecond it arrives.

**How it was found.** `curl -N --trace-time` through the gateway for the wire timing;
then a temporary log in `streamRound` of each chunk's arrival, in ms since the call.

**Wrong theories.** The new `ChatPanel` layout, Next's dev server, the gateway. The
step event crossing at once rules out the path; `curl` outside the browser rules out
the frontend.

**What changed.** Nothing. Brick 4's September `curl` check already showed segments
arriving together at the end. Separating Vertex from the SDK needs a direct
`streamGenerateContent` call (the chat model authenticates with the GCP service
account, not an API key). Left to the model-change work, which touches the same call.
