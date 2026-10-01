# 0004. Bound normalization with both a Semaphore and ffmpeg `-threads`

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §3 and Step 6c. Checked
2026-09-30: `MediaProcessingService` holds `normalisationSlots` (2 permits) around
every normalization, and the video transcode passes `-threads 2`.

## The question

How do we stop a video transcode from starving the rest of a shared 4-OCPU box
(4 JVMs + Postgres), given normalization runs in-process (ADR-0003)?

## Decision

Two limits, together. A `Semaphore` caps how many normalizations run at once. The
libx264 transcode passes `-threads 2` to cap how many cores each one uses.

## Why

"A `Semaphore` alone is NOT CPU isolation. `libx264` uses every core it can see by
default. `Semaphore(1)` with unbounded threads still starves 4 JVMs + Postgres on a
4-OCPU box. You need both: the `Semaphore` bounds *concurrency*, and `-threads N`
bounds *appetite*. One without the other doesn't work."

Since ADR-0007, the chunking ffmpeg and the transcode run at the same time, so
these limits are "load-bearing, not optional."

## Alternatives rejected

- **Semaphore only** — libx264 takes every core it can see; one transcode starves
  the box.
- **A separate service for isolation** — see ADR-0003.

## What this costs

HEVC transcodes are slower than they could be on an idle box. Uploads beyond the
permit count wait. At today's numbers, two transcodes at two threads each can
still occupy four cores; the limits stop runaway use, they don't guarantee a share
for the other services.

## Invariants this creates

- Every libx264 call passes `-threads`. A reader may see `-threads 2` as arbitrary;
  it isn't.
- Every normalization runs between `normalisationSlots.acquire()` and `release()`.
- Remux (`-c copy`) and the WAV → AAC encode don't pass `-threads`: remux does no
  encoding, and audio encoding is cheap. That's deliberate, not an omission.
