# Media playback

**Status:** Playback and normalization are on `master`. This plan holds the two
bricks left over from the legacy `MEDIA-PLAYBACK-PLAN.md` (ported 2026-09-30).
Neither has started.
**Branch:** not created yet
**Started:** 2026-07-14

## Goal

Any user can upload `mp4`, `webm`, `mp3` or `wav`, watch it back in the browser,
and a citation click seeks the player to the cited timestamp. What's left: the
normalization stage explains itself in the pipeline view, and chunk objects stop
piling up in GCS after a file completes.

## Not doing

- **Per-user quota / upload allowlist (ROADMAP E.5) — deferred.** The size and
  duration caps are per file, not a quota: one person uploading two hundred valid
  15-minute files runs up the Gemini bill and every guard reports success. Trigger:
  before the upload link is given to anyone outside the project. Do not hand out
  the link believing the caps cover this.

## Decisions

- **How does the browser play a file?** — see ADR-0001
- **Managed video service or our own ffmpeg?** — see ADR-0002
- **Where does normalization run?** — see ADR-0003
- **How is a transcode kept from starving the box?** — see ADR-0004
- **Why does normalization need local disk?** — see ADR-0005
- **Which files get normalized?** — see ADR-0006
- **Does chunking wait for normalization?** — see ADR-0007
- **When does chat send sources?** — see ADR-0008

## Open questions

None.

#### 1. Normalization events say why, and show queueing — ⬜

`NORMALISATION_STARTED` carries the reason in its message ("HEVC → H.264",
"moving moov atom", "WAV → AAC") instead of "Generating playback version". A new
`NORMALISATION_QUEUED` event is sent when `normalisationSlots` has no free permit,
before the thread blocks. Today the start event is sent only after `acquire()`, so
a queued upload shows nothing. Why, from the source: a multi-minute stage that
emits nothing reads as a hang, and the pipeline view is a product feature.

A new event type needs the enum value and a new migration in `cortex-ingestion`
extending `pipeline_events_event_type_check` (last set in V9), in the same commit.
See `.claude/rules/db-schema.md`.

**DoD:** Upload an HEVC MP4; the pipeline view shows the normalize stage with the
reason "HEVC → H.264". Start three HEVC uploads at once (2 permits); the third shows
a queued event before its start event. Ingestion starts cleanly against a real
Postgres from `docker compose up -d postgres`, since `mvn verify` won't catch a
missing migration.

#### 2. Delete chunk objects on completion — ⬜

When a file is marked `COMPLETED` in `PipelineEventsService`, delete its `chunks/`
prefix in GCS. Once transcript, visual summary and embedding are in Postgres,
nothing reads those objects again: citations seek the playback object, not the
chunks, and chunk paths are only read by `OrchestrationService` while the chunk is
processed. The source expects this to reclaim more storage than the playback copy
costs.

**DoD:** After `PIPELINE_COMPLETE` for a test file, listing its `chunks/` prefix in
the bucket returns nothing. Playback and a citation seek on that file still work.
Deleting the file afterwards still succeeds.

## Done when

1. Upload an HEVC MP4 and watch the pipeline view: the normalize stage appears with
   its reason.
2. Start three HEVC uploads together: the third shows as queued, then starts.
3. When each completes, its `chunks/` prefix is empty in the bucket.
4. Each file still plays and seeks from a citation click.
