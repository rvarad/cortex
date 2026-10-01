# 0003. Normalization lives in cortex-media-processing-service, not a new service

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §3. Checked 2026-09-30:
normalization runs inside `MediaProcessingService.processMedia`; only
`cortex-media-processing-service/Dockerfile` ships ffmpeg, and only that service
calls it.

## The question

Where should ffmpeg normalization of uploads run: in a new, fifth service, or
inside the existing media-processing service?

## Decision

Inside `cortex-media-processing-service`. No new service.

## Why

`cortex-media-processing-service` "already pulls media from GCS, runs ffprobe, runs
ffmpeg, and pushes results to GCS. Normalization *is* its job description." A
fifth service to run ffmpeg next to the service that runs ffmpeg means "a new JVM,
pom, ARM Dockerfile, compose entry, Kafka topics, config, security, CI — and zero
new capability."

## Alternatives rejected

- **A dedicated normalization service** — "The legitimate concern hiding in 'new
  service' is CPU isolation (don't let a transcode starve the AI pipeline). That
  needs a `Semaphore`, not a service" — see ADR-0004. Media-processing is already
  independently deployable if it ever needs to scale.

## What this costs

Normalization can't be scaled or deployed separately from chunking. A transcode
shares the chunking path's container memory (`mem_limit: 3g`) and CPU.

## Invariants this creates

- Every ffmpeg and ffprobe call lives in `cortex-media-processing-service`. No other
  service's image ships ffmpeg.
- CPU protection for the rest of the box is done in code (ADR-0004), not by
  process boundaries.
