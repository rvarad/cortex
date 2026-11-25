# 0015. Two things fail a file, and the pipeline never moves a terminal one

**Status:** Accepted, 2026-11-25
**Verified by:** Ported from the Decisions of `docs/plans/failed-states.md` (deleted at
merge; see git history). `FileMetadataRepositoryTest`, against Postgres, runs each status
write against a `FOR_CLEANUP` row and every write against a `FOR_DELETION` row. Tests in
media and rag check that a failure marks the file once, logs one ERROR and sends one
`PIPELINE_FAILED`, and that a lost completion trigger marks nothing.

## The question

When is a file finished for good without completing, and how does every part of the
system learn it? Before this, a file whose processing failed stayed in its last status
for ever. The dashboard showed it as processing, rag spent model calls on its later
chunks, search returned its chunks, and nothing removed its data.

ADR-0014 deferred terminal states until the boundary logs show which failures happen.
This ADR does not wait. That evidence decides retries, and a retry policy changes when a
file fails, not what failed means. The states are needed either way.

## Decision

**What failed means** is in `CONTEXT.md` (**failed**): an error stopped the file's
processing, or it made no progress for too long.

**Three places fail a file, and only these:**

- the top of the media job, on an error;
- the top of rag's chunk listener, on an error;
- the sweep's rule 1, on no progress (ADR-0016).

**These never fail a file:** a lost completion trigger (its work is saved, and rule 1
completes the file later; ADR-0014, 2026-10-30), a lost glass box event (the same
amendment), and a failed normalisation (the file completes with playback `UNAVAILABLE`
and stays searchable). The normalisation thread never fails its file. If it dies before
it writes the playback status, the file cannot complete, and rule 1 finds it.

**A boundary fails its file in the database, then tells the timeline.** It catches the
failure, marks the file, and throws the failure again, so the boundary handler still
logs it once. The mark is a conditional update from any status that is not terminal to
`FOR_CLEANUP`. Only when it changed the row does the boundary send `PIPELINE_FAILED`, as
a glass box event. If the mark throws, its exception is added to the original as
suppressed. It never replaces it.

**Two failed statuses.** `FOR_CLEANUP` is failed, with its data still there. `FAILED` is
failed and cleaned. The status is the sweep's to-do list (rule 2, ADR-0016). The API
shows `FOR_CLEANUP` as `FAILED`.

**Terminal statuses:** `COMPLETED`, `REJECTED`, `FOR_CLEANUP`, `FAILED` and
`FOR_DELETION`. The pipeline never changes them: each of its status writes changes the
row only when its status is not terminal, and returns the rows it changed. 0 means the
file is terminal or gone, and the caller stops and sends nothing. Two moves leave a
terminal status: `FOR_CLEANUP` to `FAILED` (rule 2), and any status to `FOR_DELETION`
(Delete). The lists are in `FileStatusEnum`: terminal, failed, and hidden from search.

**What a terminal file gets:**

- **No more work.** The media job reads the status when it starts, and rag reads it
  before vision and transcription. A terminal file, or a row it cannot read, gets one
  INFO line and nothing more.
- **No place in search.** Search leaves out the chunks of failed and deleted files.
- **An end on its page.** The file page's event stream closes on a terminal status. When
  the stored timeline has no terminal event, because its send was lost, the stream sends
  one built from the row. It is not stored.

**A failed file keeps its row and its timeline.** Rule 2 removes its chunk rows, the
original and the derivatives.

**The user gets one of two fixed messages** (`PipelineEventEnum`): an error on our side,
or no progress. Both say to delete the file and upload it again. The cause stays in the
log (ADR-0014). Rule 1 stores its `PIPELINE_FAILED` in the transaction that sets the
status. So a failed file with no stored `PIPELINE_FAILED` was failed by an error, and the
page shows the error message.

**JPA cannot see a `FOR_DELETION` row.** `@SQLRestriction` on `FileMetadata` makes every
JPA read treat it as gone: the file list, the file page, the playback URL, attachments,
the webhook, the completion check, and the start checks in media and rag. Hibernate 6.6
adds the restriction to `findById` and to JPQL bulk updates too (checked by a test).
Native SQL does not see it. Search and the sweep use native SQL.

## Why

**The status must not depend on Kafka.** When Kafka is down, the media job's critical
sends fail, so its files fail, and their `PIPELINE_FAILED` cannot be sent either. If
ingestion set the status when that event arrived, those files would stay processing. So
the boundary writes the row, and the event only feeds the timeline.

**A terminal status must not be written over.** A write with no status condition could
set `CHUNKED` one second after rag set `FOR_CLEANUP`. The file would then wait again for
a chunk that never comes. With the condition in every write, the order in which the
services write does not matter. A new terminal status also needs no new code:
`FOR_DELETION` joined the list, and every write already stopped on it.

**One event per failure.** The update is conditional, so only the write that moved the
file sends `PIPELINE_FAILED`. Two chunks that fail at the same time give one event. A
file that is already terminal or deleted gets none.

**The status is the to-do list.** The sweep must find the failed files that still have
data. The status says it in one place, and each move is one conditional update.

**Search drops a failed file at once, not after cleanup.** Its chunks can be partial, the
user is told to delete it, and its playback URL answers 410. An answer that cites it
sends the user to a file they cannot play.

**The entity hides deleted rows, not each read.** A filter at each read fails when one
read forgets it. Three services read file rows through JPA, and a new read gets the
hiding by default.

## Alternatives rejected

- **Ingestion sets the status when `PIPELINE_FAILED` arrives.** The status would depend
  on Kafka, and a Kafka outage is when files fail and the event cannot be sent.
- **One `FAILED` status, with a `cleaned_at` column or a marker event for the sweep.**
  Two fields that can disagree. The status alone says it.
- **A `deleted_at` column for Delete.** Every conditional write would need a second
  condition. As a terminal status, `FOR_DELETION` needed none: every write already
  stopped on it (2026-11-22; a test runs every write against such a row).
- **Fail the file when normalisation fails.** Only playback is lost, and the chunks are
  searchable. Failing it makes the user upload again and pay for every model call again.
  The same is true for a lost completion trigger: its work is saved.
- **A `FOR_DELETION` filter at each read.** Even with the restriction, one read was
  missed: attachments read chunks by file id from `media_chunk`, which the restriction on
  `FileMetadata` does not reach (found 2026-11-19). Filters at each read would miss more.

## What this costs

- One failed chunk fails its whole file, even when a second try would pass (a 429).
  Retries are still deferred (ADR-0014).
- A failed file cannot run again. The user deletes it, uploads it again, and pays for
  every model call again.
- When rag fails a file, its media job runs on to its end and keeps one of media's two
  executor slots. Rag's skip stops the model cost, but up to three chunks already in rag
  finish, and they are paid for.
- The user sees one of two fixed messages. The cause is only in the log.
- A lost `PIPELINE_FAILED` leaves a gap in the stored timeline. A page opened later makes
  up the event from the row. A page already open waits for the sweep (ADR-0016).
- A new full save of a file row would bring the overwrite back. The completion check's
  save is the one exception, and it runs under a row lock.
- Code that must see deleted rows has to use native SQL. A new read of chunks by file id
  has to check the file first.

## Invariants this creates

- Only three methods mark a file `FOR_CLEANUP`: `MediaProcessingService.failFile`,
  `OrchestrationService.failFile` and `PipelineEventsService.failIfStuck`.
- A `CompletionTriggerLostException` never fails a file. A failed glass box send never
  fails a file. The normalisation thread never fails its file.
- Every status write of the pipeline is a conditional update that returns its row count.
  A write that changed no row sends no event. The only full save of a file row is in the
  completion check, after `findByIdForUpdate` locked the row.
- Nothing in the pipeline moves a terminal file. The only moves out are `FOR_CLEANUP` to
  `FAILED` (rule 2) and any status to `FOR_DELETION` (Delete).
- No call site writes its own list of statuses. The lists are in `FileStatusEnum`.
- The API never returns `FOR_CLEANUP` (it shows `FAILED`), and never returns a
  `FOR_DELETION` row.
- A failed mark never replaces the failure it marks. It is added as suppressed.
- Rule 1 stores `PIPELINE_FAILED` with the status. A failed file without one was failed
  by an error.
- The playback writes (`updatePlaybackReady`, `updatePlaybackUnavailable`) have no status
  condition. Only `@SQLRestriction` keeps them off a deleted row. If the restriction
  goes, they need a condition.
- A new status write gets a test in `FileMetadataRepositoryTest`, against a
  `FOR_CLEANUP` row and a `FOR_DELETION` row.
