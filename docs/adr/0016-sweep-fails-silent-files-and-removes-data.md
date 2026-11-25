# 0016. A scheduled sweep fails silent files and removes failed and deleted ones

**Status:** Accepted, 2026-11-25
**Verified by:** Ported from the Decisions of `docs/plans/failed-states.md` (deleted at
merge; see git history). N is not measured: it is a guess from the code's limits (see
Why). Tests against Postgres check the silence queries. Rules 1, 2 and 3 and the
abandoned-upload delete were each seen once by hand in dev, with a short N.

## The question

Some work cannot be done by the work itself.

- **Seeing that work stopped without an error.** A container killed mid-job, a lost
  start event, a call that never returns: nothing throws, so no boundary fails the file
  (ADR-0015). ADR-0014 named this gap: a job killed with its container leaves no log.
- **Removing a file's data while something can still write it.** When rag fails a file,
  its media job runs on and writes more chunks. When the user deletes a file during its
  job, the same. A removal at that moment misses what is written after it, and a removal
  that fails is never tried again.

So something outside the pipeline must look at the files on a schedule. How does it tell
work that stopped from work that is slow, and when is it safe to remove data?

## Decision

**One scheduled job, `Sweep`, in ingestion.** Ingestion owns the schema, stores the
pipeline events, and holds the event streams of the open file pages. The first run
starts 45 minutes after startup. After that, a run starts 5 minutes after the last one
ends.

**Activity is a pipeline event.** A file's last activity is its newest
`pipeline_events.created_at`, or the row's `created_at` when it has no events. Its
**silence** is the time since its last activity. Every event starts the clock again.

**N is the silence after which a file counts as stopped.** It depends on the status:

- `UPLOADED`: 2 hours (`cortex.sweep.uploaded-silence`). A file waits in the media queue
  as `UPLOADED`, and the wait sends no events.
- Everything else (`CHUNKED`, `PROCESSING`, rules 2 and 3, the first run): 45 minutes
  (`cortex.sweep.processing-silence`).

**Each run, in this order:**

1. **Abandoned uploads.** A `PENDING` row older than 2 hours
   (`cortex.sweep.abandoned-upload-age`) is deleted, not failed. Its age counts, not its
   silence: it has no events. The row goes first, only while it is still `PENDING`.
   Then any object that landed goes.
2. **Rule 1: stuck files.** For each silent `UPLOADED`, `CHUNKED` or `PROCESSING` file,
   the sweep runs the completion check first. A file whose work is all saved completes.
   Otherwise, in one transaction, the sweep sets `FOR_CLEANUP` and stores
   `PIPELINE_FAILED` with the no-progress message, through the path that sends it to
   open pages.
3. **Rule 2: failed files are cleaned.** For each silent `FOR_CLEANUP` file, the sweep
   deletes the original and the derivatives in GCS. Then, in one transaction, it deletes
   the chunk rows and sets `FAILED`, only while the file is still `FOR_CLEANUP`.
4. **Rule 3: deleted files are removed.** For each silent `FOR_DELETION` file, the sweep
   deletes the objects in GCS, then every row of the file, in one transaction.
5. **Open pages end.** A page still open on a failed or rejected file gets the terminal
   event built from the row, and its stream closes.

The sweep handles each file in its own try. When a file fails, the sweep logs it once,
with its `fileId`, and goes on to the next file.

**Delete only marks the file.** It sets `FOR_DELETION` from any status and answers at
once. Rule 3 removes the file.

**GCS first, rows last,** in rules 2 and 3. A crash or a failed GCS call between the two
leaves the status as it was, and the next run does the work again. Deleting an object
that is gone does nothing. Abandoned uploads go the other way, on purpose: the
conditional delete of the row decides whether the webhook or the sweep wins.

**Every rule ends by changing the status or deleting the row.** So the next run does not
pick the same file again.

**The cut-offs come from the JVM clock.** Java calculates them and passes them to the
queries. The queries do not use Postgres's `now()`.

## Why

**Silence, not age.** A long file that makes progress must never fail, and a fixed age
is wrong for some file length. Most work that hangs is stopped by its own timeout
(ffmpeg's 30 minutes, rag's waits for its model calls, `KafkaSends.WAIT`), and that
fails the file at its boundary at once. Rule 1 finds the rest: a killed container, a
lost start event, a call with no timeout. So a longer N costs storage and the user's
wait, not threads.

**N is a guess from the code's limits, not a measurement** (2026-11-17). A measurement
needs real uploads, and each upload costs model calls. With one user, a guess that holds
for these limits is enough:

- **2 hours for `UPLOADED`.** The executor runs 2 jobs and queues 5, so the 7th file of a
  batch waits for 3 jobs. A job waits for its chunking and its transcode. Each ffmpeg run
  stops at `FFMPEG_PROCESSING_TIMEOUT_M` (30 minutes), so with the upload drain a job
  takes about 35 minutes at most. 3 × 35 is about 105 minutes, less than 2 hours.
- **45 minutes for the rest.** A transcode is silent for up to 30 minutes, and 15 minutes
  is the margin. The longest gap inside a run in the dev data was 4 minutes (12 files,
  2026-11-17). Rag needs no value of its own: chunks are 60 seconds (15 for the longest
  file), rag runs 3 at a time, and a video chunk took about 40 seconds. So even 7 files
  in rag at the same time wait about 23 minutes.

**The first run waits 45 minutes.** Events that waited in Kafka while ingestion was down
are stored first. Without the wait, a file whose events are still in Kafka looks silent.

**Rules 2 and 3 also wait for silence.** A job runs to its end, even for a failed or
deleted file. 45 minutes without an event means that it has stopped writing. 45 minutes
is also more than the 16 minutes a job took to fail with Kafka stopped (2026-10-23). A
job that cannot send events still writes until its next critical send fails it.

**Delete goes through the sweep.** Before, Delete deleted the rows in one transaction
and the objects after the commit. A job that still ran then wrote objects and rows that
nothing removed, and a failed GCS delete left objects that nothing knew about. Now
Delete answers at once from any status, and rule 3 tries again until it succeeds.

**An abandoned upload is deleted, not failed.** The upload did not finish, and the
pipeline did not start. There is nothing to keep, and the dashboard never showed a card
for it. The signed URL lives 30 minutes, and GCS checks it only when the PUT starts. 90
minutes more covers an upload that is still running (2 GB at about 3 Mbit/s).

**The JVM clock, because the columns have no time zone.** Ingestion writes both
`created_at` columns in the JVM's local time (IST), into `timestamp` columns. Postgres's
`now()` is UTC. A comparison with `now()` is 5½ hours wrong. Seen in dev: an event
stored at 14:57 while `now()` read 09:32 UTC.

**Every 5 minutes.** A run is a few indexed queries (an index on
`pipeline_events (file_id, created_at)`). The interval only delays what the user sees.

## Alternatives rejected

- **Age instead of silence.** A fixed age fails a long healthy file, or makes a short
  stuck file wait for hours.
- **Measure N before choosing it.** Dropped 2026-11-17: every measuring run costs model
  calls. The check in What would change our mind uses data that normal use makes.
- **Remove data at once:** clean a failed file when it fails, and delete a file's data
  when Delete is pressed. The job that still writes leaves data that nothing removes.
  Delete worked this way before.
- **Lower `FFMPEG_PROCESSING_TIMEOUT_M` to shorten N.** It is the floor for the 45
  minutes. If it is lowered without a measurement, it can fail real long files.
- **Fail an abandoned upload, then clean it.** There is nothing to keep or show. One
  delete does it.
- **Postgres's `now()` for the cut-offs.** 5½ hours wrong, as above.

## What this costs

- A file whose work died shows Failed only after N plus up to one interval: 45 to 50
  minutes, or 2 hours to 2 hours 5 minutes for a file that is still `UPLOADED`.
- A batch larger than the limits allow can fail a healthy file. The 8th file of a batch
  waits in Kafka behind 4 jobs, and can pass 2 hours. Accepted.
- A hung call is not freed. Rule 1 fails its file, but the thread stays hung and keeps
  its slot (media has two). Timeouts on outbound calls are separate work.
- The data of a failed or deleted file stays for at least 45 minutes.
- Nothing is judged for 45 minutes after ingestion starts.
- One ingestion instance only. Two instances would both run every rule. The conditional
  updates keep the statuses correct, but the GCS work doubles.
- Nothing in the code ties N to the limits it was guessed from.
- A page open on a file whose `PIPELINE_FAILED` was lost spins for up to one interval.

## Invariants this creates

- Activity is the newest pipeline event, or the row's `created_at`. A new step that can
  be silent for more than 45 minutes sends events while it runs, or N goes up.
- N holds only for the limits it was guessed from: the executor's 2 jobs and queue of 5;
  2 normalisation slots (`MAX_CONCURRENT_NORMALISATIONS`), as many as jobs, so no file
  waits silently for a slot; `FFMPEG_PROCESSING_TIMEOUT_M` at 30 minutes; 60-second
  chunks; and rag's 3 partitions. If one of them changes, guess N again.
- Rules 2 and 3 never wait less than rule 1's 45 minutes. The first run waits the same.
- Rule 1 runs the completion check before it fails a file, and stores `PIPELINE_FAILED`
  in the transaction that sets the status (ADR-0015 relies on it).
- Rules 2 and 3 delete objects first and rows last. Every rule ends with a status change
  or a row delete.
- The sweep handles each file in its own try, and logs a failure once.
- The sweep's queries are native SQL, so they see `FOR_DELETION` rows (ADR-0015). Their
  cut-offs are parameters from the JVM clock, never `now()`.
- Delete never removes data. Only rule 2 removes a failed file's data, and only rule 3
  removes a deleted file's data and rows.
- One ingestion instance.

## What would change our mind

N is a deferral of the measurement. Check it from data that normal use makes: for each
file, the gap between each pipeline event and the event before it. Leave out a gap that
starts at `PIPELINE_COMPLETE`, `PIPELINE_FAILED` or `UPLOAD_REJECTED`: that is the time
between two runs of one file. Compare a gap that ends at `PROBE_STARTED` (a queue wait)
to the `UPLOADED` N, and the other gaps to 45 minutes. When a gap comes close to its N,
or a healthy file fails by rule 1, raise N in config.

Before a second ingestion instance starts, the sweep needs a lock, so that only one
instance runs each sweep.
