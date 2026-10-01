# 0005. Normalization writes its output to a local temp file

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §3 ("RAM vs. Disk"). Checked
2026-09-30: `remuxFile`, `transcodeVideo` and `transcodeAudio` in
`MediaProcessingService` write `normalized.*` into the job's work directory, read
from the signed URL, and the file is deleted right after upload.

## The question

Can normalization stream from GCS to GCS, or does it need local disk?

## Decision

The input streams from the signed GCS URL; the original is never fully
downloaded. The output is written to a bounded temp file on local disk, uploaded
as the playback derivative, then deleted.

## Why

"RAM: never an issue. ffmpeg streams; it does not buffer the file in memory."

"`-movflags +faststart` requires a seekable output: ffmpeg writes the MP4 with
`moov` at the end, then does a second pass rewriting the file to move `moov` to the
front. To seek backwards it needs a real file on disk — it cannot be piped."

"Therefore: normalization writes a bounded temp file to local disk. Unavoidable if
we want faststart."

## Alternatives rejected

- **Fragmented MP4 (`-movflags frag_keyframe+empty_moov`)** — can be piped with no
  seeking, "but fMP4 seeking in a plain `<video>` tag is unreliable without an
  index, and seeking IS the citation feature. Bad trade."

## What this costs

Peak disk is the number of concurrent normalizations times the output size.
"The mitigation is the skip-path: most uploads are already h264 + faststart → we do
nothing at all (no disk, no CPU)" (ADR-0006). The file-size cap bounds each file
and the Semaphore bounds how many exist at once (ADR-0004).

## Invariants this creates

- The temp file sits in the job's work directory, outside its `chunks/`
  subdirectory, so the chunk directory watcher never mistakes it for a chunk.
- It is deleted as soon as the playback upload finishes. The work directory is
  removed only after both chunking and normalization are done (ADR-0007).
