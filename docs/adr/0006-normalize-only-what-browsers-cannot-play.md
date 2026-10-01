# 0006. Probe every upload and normalize only what a browser can't play

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §6. The table below is the
code as of 2026-09-30 (`MediaProcessingService.decideNormalisationStrategy`), which
differs in detail from the source's table.

## The question

Which uploads need a separate playback derivative, and which can be served as
they are?

## Decision

Decide per file, from the declared content type plus the ffprobe manifest and the
MP4's box order:

- **MP3, WebM** — pass through.
- **WAV** — transcode to AAC (`.m4a`).
- **MP4 with no real video stream** — pass through.
- **MP4, h264, `moov` before `mdat`** — pass through. The common case.
- **MP4, h264, `moov` after `mdat`** — remux: `-c copy -movflags +faststart`.
- **MP4, any other codec** — transcode to h264 (`yuv420p`) + AAC.

Pass-through serves the original; the rest write `playback/<fileId>.mp4` or `.m4a`.

## Why

"Container ≠ codec. `.mp4` is a container — it can hold h264, HEVC, AV1, ProRes.
iPhones and Macs record HEVC by default, and Chrome/Firefox won't decode it →
black player. That's why an extension/MIME allow-list is not sufficient and we must
probe the actual codec."

With `moov` at the end, the browser must download the whole file before it can
seek, so citation deep-links hang.

WAV is ~10 MB/min uncompressed; AAC is "a ~10:1 storage + bandwidth win."

The skip path means the common case costs "no disk, no CPU."

## Alternatives rejected

- **Extension/MIME allow-list alone** — HEVC in `.mp4` passes it, then plays as a
  black screen in Chrome.
- **Transcoding every MP4 that needs fixing** — when the codec is already h264, a
  remux is "seconds, lossless, trivial CPU"; a transcode is "slow, CPU-heavy. Only
  for non-h264 input."

## What this costs

Resolution and bitrate are not in the table. A short 4K h264 file under the size
cap passes through and is served as a multi-GB playback file. The source accepts
this: 1080p is the supported ceiling.

## Invariants this creates

- Every file gets a playback object: pass-through records the original's object
  name, normalization records the `playback/` object.
- The decision comes from probing the file, never from the file extension.
