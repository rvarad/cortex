# Cortex — Domain Language

Words that mean something specific here, or that collide with another meaning
elsewhere in this repo. Use these; avoid the listed alternatives.

Not a tutorial. General concepts (embedding, token, hallucination) aren't defined —
any reader already knows them, and defining them dilutes the entries that matter.

Update it when you catch yourself, or a session, using two words for one thing or
one word for two things. Not on a schedule.

## Terms

**chunk**
A ~60-second slice of one media file: transcript, visual summary, embedding,
start/end timestamps, one row in `media_chunk`.
_Not_: a read boundary in an HTTP or SSE stream. That's a **frame**.

**segment**
One piece of a generated answer, with its citation numbers — `AnswerSegmentDTO`.
_Not_: Whisper's per-~3s units in `verbose_json` (always say **transcription
segment**), and not ffmpeg's `-f segment` muxer (always say **the segment muxer**).

**source**
A chunk that an answer cites — `SourceRefDTO`: file display name, timestamp, chunk
index.
_Avoid_: reference.
_Not_: the original uploaded file (the **original**, or **master** when contrasting
with derivatives), and not a library's source code.

**citation**
The pointer inside an answer (`[1]`).
_Avoid_: chip (that's the UI affordance that renders a citation).

**original** / **derivative**
The uploaded file as it arrived is the **original** (or **master**). The normalized
playback copy and the chunks are **derivatives**. Multiple copies is the intended
design, not a smell.

**context window**
The model's input limit. Always two words.
_Not_: `MediaProcessingContext`, the per-job state object in media-processing —
always written as the full type name.

**slice**
One planning pass over part of a feature — the chunk broken into bricks in a single
planning session. Not a shipping boundary; merges are decided separately.

**brick**
One unit of work inside a slice, and one commit. Sized by what you can review in one
sitting. Leaves the system working.
_Avoid_: step. `MEDIA-PLAYBACK-PLAN.md` used "Step" for this; **brick** wins.

## Flagged ambiguities

- "step" meant both a unit of work and an SSE status event. Resolved: a unit of
  work is a **brick**; what the user sees while the agent works is a **step event**.
- "source" meant both the original upload and a cited chunk. Resolved: the upload
  is the **original**; a cited chunk is a **source**.
