# Parked

Work seen while building something and set aside, that may be done later. Each entry
says where it came from and why it was not done then. Use `TEMPLATE.md`.

When you take one up, or decide never to do it, delete its entry. Git history keeps it.

## Retry a failed chunk or job, with backoff and a dead-letter topic

**From:** failure-boundaries, 2026-10-05 (ADR-0014)
**Why not then:** The right retry depends on which failures pass on their own, and the
boundary logs did not show that yet. A retry of bad input only costs money.
**What would bring it back:** ADR-0014, What would change our mind.

## Add timeouts to the outbound calls that have none

**From:** failure-boundaries, 2026-10-05 (ADR-0014); failed-states, 2026-11-04
**Why not then:** Deferred with retries (ADR-0014). The sweep fails a file whose call
hangs, but the thread stays hung and keeps its slot. Media has two.

## Stop the media job when rag fails its file

**From:** failed-states, 2026-11-04
**Why not then:** Rag's skip already stops the model cost. What is left is the job's CPU
and one of media's two executor slots, until the job ends.

## Remove the `chunks/…` objects of completed files

**From:** failed-states, 2026-11-04
**Why not then:** Separate work, for example a GCS lifecycle rule. Only rag reads these
objects, minutes after upload, but they stay for every completed file.

## Make the webhook idempotent

**From:** failed-states, 2026-11-04
**Why not then:** Out of scope. A webhook delivered twice for a file still processing
starts a second job. The plan only stopped it from changing a terminal file.

## Check what the agent does with an attached file that later failed

**From:** failed-states, 2026-11-04
**Why not then:** Out of scope, and not checked. After cleanup, the file's chunks are
gone.

## Delete `pipeline-status.tsx`

**From:** failed-states, 2026-11-04
**Why not then:** Nothing imports it (`src/components/files/`), so it is a separate
cleanup.

## An upload progress tracker, and a better upload toast

**From:** failed-states, 2026-11-19
**Why not then:** Separate frontend work.

## Clear a cleaned file's object names

**From:** failed-states, 2026-11-19
**Why not then:** Low priority. After rule 2, a `FAILED` row keeps `object_name` and
`playback_object_name`, though the objects are gone. Clearing them needs a migration
that drops `object_name`'s `NOT NULL` (V1), the entity's `@NonNull @NotBlank` and
`nullable = false` removed, and a null check in every reader of `objectName`
(`deleteObject` fails on a null). Harmless meanwhile: a late webhook for the object
finds a `FAILED` row, logs INFO and starts nothing.

## Empty media's temp directory at startup

**From:** failed-states, 2026-11-19
**Why not then:** Low priority. The job's `finally` deletes its directory under
`/tmp/media-processing-service-chunks/` on success and on failure, but not when the
process is killed (container stopped, JVM crash). At startup no job runs, so everything
under that directory is left over.

## Log a 404 on the events stream once

**From:** failed-states, 2026-11-22
**Why not then:** Not that brick's work. Opening the event stream of a deleted file
logs two WARN lines: `ApiExceptionHandler` ("answered 404: null"), then Spring's
`ExceptionHandlerExceptionResolver` ("Resolved [ResponseStatusException: 404
NOT_FOUND]").

## Test the `fileId` in the upload response

**From:** failed-states, 2026-11-23
**Why not then:** The fix was one line (`361491a`), checked by hand in the browser.
Without the `fileId`, a finished upload's card shows only once its webhook marks it
`UPLOADED`.

## Fix the dashboard's lint error

**From:** failed-states, 2026-11-23
**Why not then:** It was there before this work. `npm run lint` reports
`react-hooks/set-state-in-effect` on the dashboard's effect that calls `fetchFiles`.
CI does not lint the frontend.
