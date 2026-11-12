# Failed states

**Status:** Planned 2026-11-03. Bricks 1-4 done 2026-11-05, brick 5 2026-11-12. Next: brick 6. The definition is in
`CONTEXT.md` (**failed**), the send kinds in ADR-0014's 2026-10-30 amendment, and the
rest under Decisions.
**Branch:** failed-states
**Started:** 2026-10-29

## Goal

When a file's processing fails (an error in the media job or in a rag chunk, or no
progress for too long), the file shows as Failed on the dashboard and on its page, with
a message that says which of the two it was. Nothing turns it back into Processing, and
rag spends nothing more on it. Its data is removed; its row and timeline stay. Deleting
a file answers at once, and its data is removed in the background, even while it is
still processing.

## Not doing

- **Retries, backoff, dead-letter topics.** One failed chunk still fails its file.
  Retry policy is its own ADR (ADR-0014), written from what the logs show.
- **Running a failed file again.** A new upload is the retry; cleanup deletes the
  original.
- **Stopping the media job when rag fails a chunk.** The job runs to its end. Rag's skip
  removes the model cost; the job's CPU and its executor slot are the cost left.
- **Freeing a hung call.** The sweep fails a file whose call hangs, but the thread stays
  hung and keeps its slot (media has two). Timeouts on outbound calls are separate work.
- **Abandoned uploads.** A `PENDING` row whose upload never finished stays. Separate
  work; the sweep can take a rule for it later.
- **Old chunk objects of completed files.** `chunks/…` stays in GCS for every completed
  file, though only rag reads it, minutes after upload. A GCS lifecycle rule is separate
  work.
- **A webhook delivered twice for a file still processing.** It still starts a second
  job. Brick 4 only stops it from changing a terminal file; idempotency stays out.
- **More than one ingestion instance.** The sweep assumes one. Two would both run every
  rule: the conditional updates keep the statuses right, but GCS work doubles.
- **A shorter wait for glass box sends while Kafka is down.** The amendment's last cost
  stays: a job stalls for minutes before its next critical send fails it.
- **The failure's cause in the UI.** The user sees one of two fixed messages; the
  exception stays in the log (ADR-0014).
- **A file page already open when `PIPELINE_FAILED` is lost.** It spins until reloaded;
  brick 8 fixes only the reload.
- **Conversations that attached a file that later fails.** Not checked what the agent
  does once that file's chunks are gone.
- **Deleting `pipeline-status.tsx`.** Nothing imports it; separate cleanup.

## Decisions

- **What failed means** — see `CONTEXT.md`, **failed**.
- **What a failed send means** — see ADR-0014, 2026-10-30 amendment: critical,
  completion trigger, glass box.
- **Why now, before the evidence ADR-0014 asks for** — that evidence decides retries. A
  retry policy changes when a file fails, not what failed means, so the states are
  needed either way.
- **Where a file fails** — at the top of the media job, at the top of the rag listener,
  and in sweep rule 1. Nowhere else. The normalisation thread never fails its file: a
  failure that leaves playback `PENDING` is found by rule 1.
- **How a boundary fails its file** — catch at the top of the unit, mark the file,
  rethrow; the boundary handler still logs once. `BackgroundTask` and `KafkaFailures`
  don't change: they hold no policy (ADR-0014, 2026-10-12). If marking throws, its
  exception is added to the original as suppressed and never replaces it.
- **A lost completion trigger never fails its file.** Its work is saved; rule 1
  completes the file later.
- **The boundary writes the status itself, in the database**, then sends
  `PIPELINE_FAILED` as a glass box event. The status does not depend on Kafka.
- **Only the write that moves the file sends `PIPELINE_FAILED`.** The update is
  conditional; when it changes no row (already terminal, or deleted), nothing is sent.
  Two chunks failing at once give one event.
- **Two failed statuses** — `FOR_CLEANUP` (failed, data still there), then `FAILED`
  (failed, cleaned). The status is the sweep's to-do list: no marker column, no marker
  event.
- **Terminal statuses** — `COMPLETED`, `REJECTED`, `FOR_CLEANUP`, `FAILED`, and
  `FOR_DELETION` from slice 4. The pipeline never changes them. The only moves out:
  `FOR_CLEANUP` → `FAILED` (rule 2) and any status → `FOR_DELETION` (Delete). Every
  move is a conditional update.
- **The lists live in `FileStatusEnum`** — the terminal list and the failed list, with
  `isTerminal()` and `isFailed()`. No call site writes its own list; conditional updates
  and the native search queries take the list as a parameter.
- **The API shows `FOR_CLEANUP` as `FAILED`** — one line, where `FilesService` builds
  the DTO. The frontend never sees `FOR_CLEANUP`.
- **Rag skips terminal files** — it reads the status before vision and transcription:
  INFO, no model call, no event. Up to three chunks already running finish.
- **The media job skips terminal files** — the same check when it starts, before the
  probe. A file the sweep failed while it waited in the queue never runs.
- **What a failed file keeps** — its row and its timeline. Rule 2 deletes its
  `media_chunk` rows and its original, `chunks/…` and `playback/…` objects.
- **Search leaves failed files out** — before and after cleanup, a failed file's chunks
  never reach an answer.
- **The sweep** — one scheduled job in ingestion, the first in the repo. Rule 1
  (stuck), rule 2 (clean), rule 3 (delete, slice 4). Each rule changes the status when
  it is done, so it never picks the same file twice.
- **Last activity** — the newest `pipeline_events.created_at` for the file, or the
  row's `created_at` when it has none. Rules 2 and 3 also wait for N without activity,
  so work still running has stopped writing.
- **Rule 1 runs the completion check first** — a file whose work is all saved but whose
  last trigger was lost completes; it is not failed.
- **Rule 1 writes the status and its `PIPELINE_FAILED` in one transaction**, through
  the path that broadcasts to open pages. So a failed file with no `PIPELINE_FAILED`
  was failed by an error at a boundary.
- **The first sweep run waits N after startup**, so events that waited in Kafka while
  ingestion was down are saved before anything is judged.
- **N measures silence, not age** — every event starts the clock again, so a slow file
  that makes progress never fails by rule 1. Work that hangs is stopped by its own
  timeouts (ffmpeg, the model calls, `KafkaSends.WAIT`), which fail the file at once;
  rule 1 only finds files where nothing runs. So a longer N costs storage and wait
  time, not threads.
- **N is the longest silent gap plus a margin** — queue waits and a video transcode
  included — and longer than a job takes to fail with Kafka stopped (16 minutes in the
  failure-boundaries Done when, 2026-10-23). With Kafka down no events arrive, so a
  shorter N lets rule 2 clean a file while its job still writes. Measured in brick 10,
  not guessed. A batch larger than the one measured can fail a healthy file; accepted.
- **`FFMPEG_PROCESSING_TIMEOUT_M` is not above N** — a running transcode is silent, so
  that timeout is the floor for N. Brick 10 lowers it to fit the longest chunking or
  transcode it sees, so N can come down with it.
- **The sweep runs every 5 minutes.** One run is one indexed query; the interval only
  adds delay. N and the interval are config properties.
- **Reads hide `FOR_DELETION` with `@SQLRestriction` on `FileMetadata`** — every JPA
  read, now and later, treats the row as gone; a filter at each read fails when one
  read forgets it. The sweep uses native SQL, which the restriction does not touch.
  Nothing but rule 3 changes a `FOR_DELETION` row, so JPQL updates need not see it.
- **Rules 2 and 3 delete GCS objects first and rows last.** A crash between the two
  leaves the status as it was, and the next run does it again; deleting a missing
  object does nothing.
- **When the event is missing, the page learns the outcome from the row** — the SSE
  endpoint sends the missing terminal event, built from the row, then closes. Not
  stored. Covers `REJECTED` too.
- **Spinners stop when the run ends** — a stage or chunk step that started and never
  completed shows warned, as the probe stage already does.
- **Two messages** — an error: "Processing stopped because of an error on our side.
  Delete this file and upload it again." No progress: "Processing stopped making
  progress. Delete this file and upload it again." An error leaves one ERROR line with
  the `fileId`; no progress leaves none.
- **Delete goes through the sweep (slice 4)** — Delete sets `FOR_DELETION` (a status,
  not a `deleted_at` column) and answers. Rule 3 deletes the objects, then the rows,
  trying again until it succeeds.
- **Tests** — test first, every brick: see it fail, then pass. Mockito fakes that throw;
  no fault hooks in `src/main`. "Logged once" is checked with a `ListAppender`. Queries
  are tested against Postgres in ingestion (Testcontainers). Claude writes the tests;
  Varad reviews and runs them.

## Slices

### Slice 1 — every send has a kind

**When this slice is done:** a lost glass box event no longer stops the work it belongs
to, and a lost completion trigger reaches its boundary as a failure of its own type.
Critical sends are unchanged.

#### 1. Glass box sends are caught where they are sent — ✅

Ingestion, media and rag each get one way to send a glass box event. It waits for the
broker like every send; on failure it logs one WARN and returns, with a comment on the
catch saying why carrying on is correct. Every glass box event moves to it: ingestion's
`PIPELINE_STARTED` and `UPLOAD_REJECTED`; media's `PROBE_*`, `CHUNKING_STARTED`,
`NORMALISATION_STARTED`, `CHUNK_UPLOAD_*`, `MEDIA_CHUNK_READY` and `UPLOAD_REJECTED`;
rag's `VISION_ANALYSIS_*` and `TRANSCRIPTION_*`.

**DoD:** In media, a test makes the `PROBE_COMPLETE` send fail: the job still chunks,
with one WARN and no ERROR. In rag, a test makes `VISION_ANALYSIS_STARTED` fail: the
chunk is still embedded and `EMBEDDING_COMPLETE` is sent. In ingestion, the webhook
answers 200 when `PIPELINE_STARTED` fails, and the file ingestion event was sent. Every
`kafkaTemplate.send` in `src/main` is in a glass box helper, a trigger helper (brick
2), or one of the two critical sends.

#### 2. Completion triggers fail with their own type — ✅

The four trigger sends (`CHUNKING_COMPLETE`, `NORMALISATION_COMPLETE` and
`NORMALISATION_FAILED` in media, `EMBEDDING_COMPLETE` in rag) go through one helper per
service. On failure it throws one exception type for a lost trigger, with the send's
exception as its cause. The type lives in `cortex-common`'s `kafka` package, next to
`KafkaSends`, because media and rag both throw it. Boundaries still log it once at
ERROR; nothing reads the type until slice 2.

**DoD:** Tests make each send fail: `CHUNKING_COMPLETE` (the media job's boundary),
`NORMALISATION_COMPLETE` (the normalisation thread's boundary) and `EMBEDDING_COMPLETE`
(rag's Kafka report). Each gives one ERROR whose exception, or a cause of it, is the
trigger type, with the send's exception under it.

### Slice 2 — a file can fail

**When this slice is done:** an error in the media job or in a rag chunk makes the file
failed. The dashboard and the file page say so, with the error message and no spinners.
Nothing turns a failed file back, rag spends nothing more on it, and search leaves it
out. Until slice 3 a failed file stays `FOR_CLEANUP`, with its data.

#### 3. The statuses and their lists — ✅

A migration adds `FOR_CLEANUP` to the `file_status` CHECK constraint, and the entity's
`columnDefinition` matches. `FileStatusEnum` gets `FOR_CLEANUP`, the terminal list, the
failed list, `isTerminal()` and `isFailed()`. The file list shows `FOR_CLEANUP` as
`FAILED`, with the reason on that line. The checks that exist use the lists: the SSE
endpoint closes on any terminal status, the completion check stops on one, and the
playback URL answers 410 for a failed file as it does for a rejected one.

**DoD:** All three JPA services start on the migrated database. Tests: the completion
check leaves a `FOR_CLEANUP` file as it is; the file list returns `FAILED` for a
`FOR_CLEANUP` row; the playback URL answers 410 for `FOR_CLEANUP` and `FAILED`. In the
browser, a file set to `FOR_CLEANUP` by hand in psql shows the Failed chip and no Play
button, and the dashboard stops polling.

#### 4. Nothing writes over a terminal status — ✅

Every status write that is a full save becomes a conditional update that changes the
row only when its status is not terminal: the webhook's `UPLOADED` and `REJECTED`,
media's two `REJECTED` writes, and `updateChunkingResult`. A write that changes no row
sends no event. The completion check needs nothing more: it stops on a terminal status
under its row lock (brick 3).

**DoD:** Repository tests in ingestion (Testcontainers): each write leaves a
`FOR_CLEANUP` row as it is, and still works on a row that is not terminal. A test: the
webhook for a `FOR_CLEANUP` file answers 200 and sends nothing.

#### 5. Search leaves failed files out — ✅

The semantic and the lexical query leave out chunks whose file is in the failed list,
passed in as a parameter.

**DoD:** In ingestion's `MediaChunkRepositoryTest`: a chunk that both queries find while
its file is `COMPLETED` is found by neither once the file is `FOR_CLEANUP` or `FAILED`.

#### 6. The media job fails its file — ⬜

At the top of the job, outside its `finally` (so the job's own threads have stopped), a
failure other than a lost trigger marks the file: a conditional update from a status
that is not terminal to `FOR_CLEANUP`. When the update changed the row, the job sends
`PIPELINE_FAILED` (glass box) with the error message. The failure then goes on to the
boundary, which logs it once. When the job starts, a file that is already terminal is
logged at INFO, and the job returns before the probe.

**DoD:** Tests: the GCS read of the file's first 4 MB throws → the file is
`FOR_CLEANUP`, one ERROR, one `PIPELINE_FAILED`; the `CHUNKING_COMPLETE` send fails →
the file stays `CHUNKED`, no `PIPELINE_FAILED`; marking throws → the boundary's one
ERROR carries the original exception, with the marking failure suppressed; a job for a
`FOR_CLEANUP` file never calls ffprobe.

**Check first (found 2026-11-05):** the job's `finally` does not wait for its watcher and
upload dispatcher. On a failed job they run on for up to 1 s (seen in
`MediaJobUploadFailuresTest`: the watcher logs after the job ends). Not seen, only read
in the code: a job that fails before cleanup's sweep leaves its last chunk unqueued; the
late watcher queues it after `finally` clears the queue, and the dispatcher, still in its
loop, uploads it. That chunk of a failed file gets a GCS object, a `media_chunk` row and
an embedding. If a test shows it, the `finally` waits for both helpers.

#### 7. A failed chunk fails its file; rag skips failed files — ⬜

The rag listener does the same as brick 6: a failure other than a lost trigger marks the
file and, when the update changed the row, sends `PIPELINE_FAILED`. The failure goes on
to the Kafka error handler, which reports it and skips the record, as now. Before vision
and transcription, the listener reads the file's status; for a terminal file it logs
INFO and returns, with no model call and no event.

**DoD:** Tests: vision throws → the file is `FOR_CLEANUP`, one ERROR from the Kafka
report, one `PIPELINE_FAILED`; a second chunk of the same file fails → no second
`PIPELINE_FAILED`; marking throws → the record is still reported once and skipped, not
delivered again; a chunk of a `FOR_CLEANUP` file makes no vision, transcription or
embedding call; the `EMBEDDING_COMPLETE` send fails → the file is not marked.

#### 8. The file page always learns how the run ended — ⬜

When a page connects to a terminal file whose history has no terminal event, the SSE
endpoint sends one, built from the row, before it closes: `PIPELINE_FAILED` with the
error message for a failed file (rule 1 stores its own event, so a missing one means an
error), and `UPLOAD_REJECTED` with a message for the rejection reason for a rejected
one. It is not stored.

**DoD:** Delete a failed file's `PIPELINE_FAILED` row in psql and reload its page: it
shows "Pipeline failed" with the error message, and no "connection lost" line. The same
for a rejected file whose `UPLOAD_REJECTED` row was deleted. A test covers both.

#### 9. Stages that never completed stop spinning — ⬜

On the file page, once the run has ended, a stage or chunk step that started and never
completed shows warned ("did not complete"), as the probe stage already does. Frontend
only.

**DoD:** In the browser, a file that failed while chunking shows no spinner anywhere on
its page; the chunking stage and the chunk steps that were running show warned. A
completed file's page looks as before.

### Slice 3 — the sweep

**When this slice is done:** a file that stops making progress fails by itself, and a
failed file's data is removed. Neither depends on an event arriving.

#### 10. Measure the longest silent gap — ⬜

From the stored events, find the longest time a healthy file goes without a pipeline
event: the largest gap between consecutive `created_at` values of one file. Cases:
seven files uploaded at once (two run, five wait in the media queue); the longest
allowed file (900 s) that needs a video transcode; two files whose chunks wait in rag at
the same time; and, with Kafka stopped, the time from a job's last event to its failure.
Five runs each. The result goes to `docs/findings/`; N comes from it. Also record the
longest chunking and transcode, and lower `FFMPEG_PROCESSING_TIMEOUT_M` to fit them
with a margin, not above N.

**DoD:** A findings file with the longest gap seen in each case, N with its margin, and
the new ffmpeg timeout. The longest file still chunks and transcodes under it.

#### 11. Rule 1: a stuck file fails — ⬜

Ingestion's first scheduled job: `@EnableScheduling`, a first run N after startup, then
one run every 5 minutes. N and the interval are config properties. A migration adds an index on `pipeline_events (file_id,
created_at)`. For `UPLOADED`, `CHUNKED` and `PROCESSING` files with no activity for N:
run the completion check again; if the file is still not complete, set `FOR_CLEANUP`
and store `PIPELINE_FAILED` with the no-progress message, in one transaction, through
the path that broadcasts to open pages.

**DoD:** Tests: a file with no activity for N and chunks left is `FOR_CLEANUP` with one
`PIPELINE_FAILED`; a file with all chunks `COMPLETED` and playback `READY` becomes
`COMPLETED`; a file with recent activity is left alone; nothing runs before N after
startup. In the browser, a file whose media container was stopped mid-job shows Failed
with the no-progress message after N plus the interval, and no log has an ERROR line
for it.

#### 12. Rule 2: a failed file is cleaned — ⬜

For `FOR_CLEANUP` files with no activity for N: delete the original, `chunks/…` and
`playback/…` objects; then, in one transaction, delete the file's `media_chunk` rows and
set `FAILED`, only if the status is still `FOR_CLEANUP`.

**DoD:** Tests: a cleaned file is `FAILED`, has no `media_chunk` rows, and keeps its row
and events; a GCS delete that throws leaves it `FOR_CLEANUP`, and the next run cleans
it; a second run selects nothing. Locally, after a failure and N plus the interval, the
bucket has no objects for the file, and its card and timeline are still there.

### Slice 4 — Delete goes through the sweep

**When this slice is done:** Delete answers at once. The sweep removes the file's data
and rows, trying again until it succeeds, and a delete during processing leaves nothing
behind.

#### 13. Rule 3, and deleted files are hidden — ⬜

A migration adds `FOR_DELETION` to the CHECK constraint. The enum gets it, and it joins
the terminal list and the statuses that search leaves out. `@SQLRestriction` on
`FileMetadata` hides `FOR_DELETION` rows from every JPA read: the file list, the events
stream, rename, the playback URL, attachments, the webhook and the completion check.
Media's start check and rag's skip treat a missing row like a terminal status: INFO and
return. Rule 3: for `FOR_DELETION` files with no activity for N, delete the GCS objects,
then all the file's rows, in native SQL. Nothing sets `FOR_DELETION` yet.

**DoD:** A file set to `FOR_DELETION` in psql leaves the dashboard at once; its page,
rename and playback URL answer 404; search and attachments don't return it; after N
plus the interval its objects and rows are gone. Tests: a GCS delete that throws keeps
the rows, and the next run deletes everything; a media job and a rag chunk for a
`FOR_DELETION` file log INFO, no ERROR, and do no work; the native search queries still
return the chunks of other files.

#### 14. Delete marks the file — ⬜

The Delete endpoint sets `FOR_DELETION` from any status and answers. The transaction
that deleted the rows, and the after-commit GCS delete, go.

**DoD:** Delete a file while it is chunking: the card goes at once; the job's writes
change nothing; after the job ends and N plus the interval, the bucket has no `chunks/…`
objects for it and no rows remain. Delete a `FOR_CLEANUP` file while rule 2 cleans it:
it ends deleted, not `FAILED`.

## Done when

In dev, with `docker compose up -d postgres redis kafka` and the services on the dev
profile. The webhook is called by hand: Pub/Sub can't reach localhost. Steps 1, 2, 4 and
5 use a short N and a short MP4 that plays as it is (no transcode, so no long silent
step). Steps 3, 6 and 7 use the real N: a short one there can clean or delete a file
while its job still writes.

1. **An error.** Set a wrong Gemini key for rag only and upload the short MP4. Within a
   minute the card shows Failed, and the page shows "Pipeline failed" with the error
   message and no spinner. Rag logs one ERROR for the chunk that failed and an INFO skip
   for each later chunk, and makes no more model calls. After N plus the interval the
   status is `FAILED`, the bucket has no objects for the file, and `media_chunk` has no
   rows for it; the card and the timeline are still there.
2. **No progress.** Upload a file and stop the media container while it chunks. After N
   plus the interval the card shows Failed with the no-progress message, and no log has
   an ERROR line for the file. Cleanup follows as in step 1.
3. **Kafka down.** Stop Kafka while a file chunks, and keep it stopped until the card
   shows Failed. Start Kafka and reload the file's page: "Pipeline failed" with the
   error message, not "connection lost". No objects for the file are left after cleanup.
4. **A completed file stays completed.** A file that completed before step 1 is still
   `COMPLETED` after 2N, with its objects and rows.
5. **A lost trigger.** Set a completed file back to `PROCESSING` in psql. After N plus
   the interval it is `COMPLETED` again, not Failed.
6. **Delete during processing.** Delete a file while it chunks. The card goes at once;
   after the job ends and N plus the interval, the bucket has no objects for it and no
   rows remain.
7. **Queues don't fail healthy files.** Upload seven files at once. All seven complete.
   Five runs.
