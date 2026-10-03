# Findings

Things that turned out to be true about the world — usually someone else's world:
ffmpeg, Gemini, Spring, Postman, Chrome, ripgrep — that documentation didn't say
and that cost time to discover.

A **finding** is a fact. An **ADR** is a choice. A finding can only be superseded
by a better observation; a decision can be reversed.

Append-only, newest at the bottom. Use `TEMPLATE.md`. Split this file by area only
when it gets genuinely unwieldy — past four or five hundred lines. Premature filing
structure is friction, and friction means findings stop getting written.

**A finding you need loaded every session isn't a finding any more, it's a rule.**
Those graduate to `.claude/rules/` or to a comment on the line they're about. This
file is the archive you read when you're back in that area, or when a symptom
recurs.

---

## 2026-08-15 — Vertex/Gemini vision decodes HEVC-in-MP4 chunks

**Area:** gemini
**Cost:** Shaped a design. The first playback plan (2026-07-14) made every HEVC
upload finish a full transcode before chunking, only to protect Vertex from HEVC.

**What was seen.** Chunking an HEVC original with `-c copy` produces HEVC chunks.
The 2026-07-14 plan assumed Vertex "may not decode" them, "silently breaking vision
on exactly the files Apple users upload."

**What was actually happening.** Vertex decodes them. An HEVC test chunk came back
with a coherent `visual_summary`.

**How it was found.** Made an HEVC test file with ffmpeg (`libx265 -tag:v hvc1`),
pushed it through the pipeline, and read the `visual_summary` rows it produced.
Recorded as one test file; the source doesn't say it was repeated.

**Wrong theories.** "Vertex may not decode HEVC chunks" — an assumption, never
tested until this run.

**What changed.** Normalization moved off the critical path and runs in parallel
with chunking, which reads the original whatever the codec: ADR-0007. Still in
place as of 2026-09-30 — `MediaProcessingService.startFFmpegChunkingProcess` is
given the original's signed URL. If vision on an HEVC file ever returns an empty or
incoherent summary, re-test this first.

## 2026-04-24 — A dispatcher thread that stops on a shared flag gets revived by the next job (concurrency bug)

**Area:** spring
**Cost:** Corrupt data. One file's chunks were uploaded into another file's GCS
folder and saved in `media_chunk` under the wrong `fileId`. Fixed four days after
the report, in `fea2188` (2026-04-28).

**What was seen.** Chunks from one video in another video's `chunks/` folder in
the bucket.

**What was actually happening.** `MediaProcessingService` is a singleton, and the
job state (upload queue, chunk maps, `isRunning`) lived in fields on it. Jobs ran
one at a time on the Kafka listener thread, so no two jobs overlapped. The upload
dispatcher looped while `isRunning` was true or the queue was non-empty, waiting up
to one second per poll, with the job's `objectName` and `fileId` fixed as
parameters when the thread started. When job A finished it set `isRunning` to
false, but dispatcher A was still inside its one-second poll. If the next file's
event was already waiting, `processMedia` for B set `isRunning` back to true on its
first line, before dispatcher A woke. Dispatcher A saw true and kept going, now
pulling B's chunks off the shared queue and uploading them under A's name. It never
stopped, so every back-to-back upload added another stale dispatcher.

**How it was found.** Looked in the GCS bucket and saw the wrong chunks in a
file's folder. The mechanism above comes from reading the code as it was before the
fix (`git show fea2188^`), not from rerunning it.

**Wrong theories.** The original bug report blamed two jobs running at the same
time, with the second job's `clear()` wiping the first. With the config in git at
the time that couldn't happen: `MediaEventConsumer` called `processMedia` directly
on the single listener thread, with no executor and no listener concurrency set.
The shared state was the cause; the overlap was not the trigger.

**What changed.** `fea2188` moved all job state into `MediaProcessingContext`, one
per job, and the watcher and dispatcher loops now read that job's own `isRunning`.
A new job can't revive an old job's threads. Still in place as of 2026-09-30.
The same commit added `mediaProcessingExecutor` (2 threads), so jobs now do run at
the same time, which makes the per-job state load-bearing. One piece was not moved:
`uploadSlots` is still a field on the service, and cleanup uses it to wait for
"all uploads", which includes other jobs' uploads. Tracked in
`docs/plans/per-job-upload-wait.md`.

**The general lesson.** A stop flag has to belong to the thing it stops. If a
thread's exit condition reads state the next job also writes, the next job can
cancel the stop.

## 2026-09-09 — Spring AI's google-genai chat starter builds our Vertex `Client` before `@Value` placeholders are resolved

**Area:** spring
**Cost:** Every Vertex call in rag-orchestration failed: the new agent and the
existing chat path alike. Found during the Spring AI migration (Brick 1.5).

**What was seen.** After adding `spring-ai-starter-model-google-genai`, every Vertex
call failed with `UnknownHostException: ${spring.cloud.gcp.location}-aiplatform.googleapis.com`.
The `@Value` placeholders in `GeminiConfig` had been injected as literal text.

**What was actually happening.** Spring AI 1.1.2 guards its cached-content bean with
`CachedContentServiceCondition`, which calls `getBean()` while the condition is being
evaluated. That evaluation runs inside `ConfigurationClassPostProcessor`, a
`BeanFactoryPostProcessor`. Resolving it builds `GoogleGenAiChatModel`, which needs
the `Client`, which builds `GeminiConfig.genAiClient` — all before
`PropertySourcesPlaceholderConfigurer` (also a BFPP) has run, so no `${...}` can be
resolved. Spring says so a line later: "Cannot enhance @Configuration bean
definition 'geminiConfig' since its singleton instance has been created too early."
A `Condition` calling `getBean()` is an upstream anti-pattern. Nothing in our code was
wrong: `GeminiConfig` was byte-identical to the version that worked on 2026-09-07.

**How it was found.** A stack trace taken inside the bean method, which showed
`CachedContentServiceCondition.getMatchOutcome` → `ConditionEvaluator.shouldSkip` →
`ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry` →
`PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors`. The old chat
path breaking the same way proved it predated the agent work.

**What changed.** `GeminiConfig.genAiClient` reads its config from `Environment`
instead of `@Value`, with a null check that names the missing properties.
`Environment` is populated before any bean exists, so bean ordering can't catch it
out. Still in place as of 2026-10-01, with a comment on the line saying why. Don't
generalise it: `@Value` stays the default everywhere else, and only a bean known to be
dragged into the post-processor phase needs `Environment`. Don't revert it when
upstream fixes the condition either: the `@Value` version "worked by luck of
classpath ordering, not by design."

**The general lesson.** An unchecked bad value travels. The literal `${...}` went
through the region, into a URL, into DNS — four layers — before anything complained,
and the error looked like a networking problem. A required value that is absent must
stop things immediately. And a shared bean fails widely: "Does the old path break the
same way?" is the cheapest possible bisect.
