# 0001. Playback is a signed GCS URL; media bytes never pass through the backend

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` §1 and Step 5. Checked against
`GcsStorageService.signPlaybackUrl` (cortex-ingestion) and `FilesController` on
2026-09-30.

## The question

How does a user's browser play an uploaded file, and jump to the timestamp a
citation points at?

## Decision

The backend mints a time-limited V4 signed GCS URL for `GET`, returned by
`GET /api/v1/files/{fileId}/playback-url`. The frontend puts it in a `<video>` or
`<audio>` element. The browser issues HTTP Range requests and GCS answers
`206 Partial Content`. There is no streaming server.

## Why

"There is no streaming server. You do not proxy bytes through Spring." Range
requests are what make it streaming: playback starts after a few hundred KB, and
seeking is free — when the user jumps to 3:00, the browser range-fetches the bytes
around that offset. So a citation deep-link is just setting the player's current
time to the source's start time. "You don't implement seeking. You inherit it."

"The signed URL's expiry *is* the access control."

## Alternatives rejected

- **Proxying the bytes through the backend** — "it would burn server bandwidth and
  tie up threads for the whole video."

## What this costs

A signed URL is a bearer token: once issued, whoever holds it can watch. If it
expires mid-watch, the next range request fails with 403 and playback stalls, so
the expiry has to outlast a viewing — ~6h, set by
`cortex.media.playback-url-expiry-hours`. A leaked URL is playable that long.

## Invariants this creates

- No endpoint returns media bytes. The playback endpoint returns a URL.
- The ownership check (`findByIdAndUserId`) runs before signing. "All access
  control must happen BEFORE signing."
- The playback signer does not use `withExtHeaders`. The upload signer in the same
  class does, to force a `Content-Type`; `<video>` won't send that header, so a
  playback URL signed that way returns 403. Don't copy the upload signer's options.
