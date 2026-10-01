# 0008. Chat streams each source just before the first segment that cites it

**Status:** Accepted, 2026-07-14
**Verified by:** Ported from `MEDIA-PLAYBACK-PLAN.md` Step 0. Checked 2026-09-30:
`ChatService.streamAnswer` emits `source` events inside the segment callback,
deduplicated per stream; `SourceRefDTO` has no transcript or visual summary.

## The question

When, and how much of, the chunks behind an answer should the chat stream send to
the browser?

## Decision

No sources up front. Each source is sent as a `source` event the first time a
segment cites it, immediately before that segment, and at most once per stream.
`SourceRefDTO` carries only what a citation needs: source number, file id, file
display name, chunk index, start and end time.

## Why

The old stream sent a `sources` event with every search result first. With a
whole file stuffed into context that was "~100 chunks × (transcript +
visualSummary) ≈ 200 KB — literally the entire file's text — shipped to the browser
before a single token of the answer streams." The frontend didn't use the
transcript or visual summary, and "of 100 sources, the answer typically cites ~5."

After: ~1.5 KB, and the first answer text arrives straight away.

"Even in the worst case (a summary that eventually cites all 100), lazy still wins:
the sources arrive spread through the stream instead of stacked in front of it —
you never block the first token."

The mechanism "is agnostic to how the context was assembled": whole-file or
retrieved top-N, sources are a list and citations are indices.

## Alternatives rejected

- **One `sources` event with every search result, up front** — ~200 KB before the
  first token; the user stares at a blank screen.

## What this costs

The client has to keep a map from source number to source as events arrive. The
full list of sources is only known when the answer ends.

The blocking path, `ChatService.generateAnswer`, was meant to return only the union
of cited sources. As of 2026-09-30 it still returns every search result, so the two
paths behave differently.

## Invariants this creates

- "Always emit the `source` BEFORE the `segment` that cites it." Otherwise the
  frontend renders a citation pointing at nothing.
- Citations are sanitized to the valid range before a source is looked up.
  Citation numbers are 1-based.
- A source is sent at most once per stream.
