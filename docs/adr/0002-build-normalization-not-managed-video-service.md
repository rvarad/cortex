# 0002. Build normalization ourselves instead of buying a managed video service

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §2. Vendor numbers are as the
source recorded them (July and August 2026) and were not re-checked. Code checked
2026-09-30: normalization runs in `MediaProcessingService`; no video vendor SDK in
any `pom.xml`.

## The question

Should browser-playable copies of uploads come from a managed video service, or
from our own ffmpeg?

## Decision

Build it: a conditional normalize step in the service that already runs ffmpeg
(ADR-0003, ADR-0006).

## Why

"There is no genuinely free managed service that accepts arbitrary user video at
realistic sizes. Free tiers are built for images; video is 250–500× more expensive
per unit, or hard-capped at ~100 MB."

## Alternatives rejected

- **Cloudinary (free tier)** — max video file size on Free is 100 MB (≈3 min of
  1080p). Video burns credits at 1 credit = 250 HD video-seconds; 25 credits/mo
  ≈ 104 min of HD in total, before storage and bandwidth.
- **Cloudflare Stream** — works well, including upload-from-URL, but no free tier:
  $5/1,000 min stored + $1/1,000 min delivered, with a $5/month minimum.
- **Mux** — trial credits, then premium pricing.
- **Bunny Stream** — ~$1/mo + usage. Cheap, still not free.

## What this costs

We own the transcode CPU, the temp disk and the failure handling on a shared
4-OCPU box (ADR-0004, ADR-0005), and we maintain ffmpeg command lines.

## Invariants this creates

- No playback path depends on an external video vendor. Derivatives live in our
  GCS bucket and are served through our own signed URLs (ADR-0001).

## Amendments

### 2026-08-15 — Re-evaluated after finding services that advertise free tiers; BUILD reconfirmed

- **ImageKit** — signed URLs, HEVC input and ABR are all on free, but video
  processing is capped at 500 units/mo = 250 s of 1080p per month. It can't process
  even one 15-min file.
- **Publitio** — does transcode HEVC → seekable h264 mp4 on free, but free-tier
  playback URLs are public. Fails the requirement that playback is owner-scoped and
  expiring.
- **api.video** — unlimited sandbox, but watermarks every video.
- **Publitio as a transcode-only engine** — rejected: it "adds an external async
  vendor to offload a transcode that only hits the HEVC minority and is already
  bounded by the Semaphore + `-threads`. Not worth the dependency."
