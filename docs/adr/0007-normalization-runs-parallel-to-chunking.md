# 0007. Normalization runs in parallel with chunking; chunking reads the original

**Status:** Accepted, 2026-08-15
**Verified by:** Ported from the 2026-08-15 block in `MEDIA-PLAYBACK-PLAN.md` §7.
Checked 2026-09-30 in `MediaProcessingService.processMedia`: normalization starts on
a virtual thread, `startFFmpegChunkingProcess` takes the signed URL, and the method
joins the normalization thread before returning. Rests on the 2026-08-15 finding
that Vertex decodes HEVC chunks (`docs/findings/README.md`).

## The question

Does chunking have to wait for the h264 playback copy, or can it chunk the
original upload whatever its codec?

## Decision

Chunking always reads the original from its signed URL. "Normalization is a
derivative, not a dependency": it runs on its own virtual thread, at the same time
as chunking, only to produce the browser playback copy. `processMedia` waits for
both before returning, so the Kafka message isn't acked until both finish.

## Why

"Empirically verified that Vertex/Gemini decodes HEVC-in-mp4 chunks... That removes
the *only* reason chunking needed the normalized h264 output."

"The multi-minute HEVC transcode no longer blocks the AI pipeline — chunks upload
from the stream immediately, so Gemini/Whisper start right away."

Waiting for both means "a crash mid-transcode retries cleanly instead of orphaning
a file at `CHUNKED`-but-never-playable."

## Alternatives rejected

- **Normalize first, then chunk from the local h264 file** (the 2026-07-14 design) —
  HEVC files paid the whole transcode before any chunk reached Gemini or Whisper.
  Its reason, that Vertex might not decode HEVC, was disproved by the finding above.

## What this costs

- Normalization is its own failure domain. A failed transcode must be caught and
  recorded, or playback silently never becomes available while chat and search
  work fine.
- The chunking ffmpeg and the transcode overlap on the CPU, so ADR-0004's limits are
  load-bearing.
- The Kafka message is held for the length of the transcode.

## Invariants this creates

- Chunking never reads the normalized file. `startFFmpegChunkingProcess` gets the
  signed URL of the original.
- The normalization thread catches its own exceptions, sets `playback_status` to
  `UNAVAILABLE`, and only then sends `NORMALISATION_FAILED`.
- `processMedia` joins the normalization thread before it returns.
- Chunk cleanup deletes only the `chunks/` subdirectory. The work directory may
  still hold the normalization output.
