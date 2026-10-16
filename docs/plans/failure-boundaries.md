# Failure boundaries

**Status:** Slice 1 done (bricks 1–7). Slice 2 done (bricks 8–11). Slice 3
planned (bricks 12–17): bricks 12–13 done; next is brick 14.
Every media thread, the media job, and each conversation answer run inside
`BackgroundTask`; every Kafka listener reports a failed or unreadable record once,
through the shared `KafkaFailures` report, and moves on; every Kafka send waits for
the broker. **Before merging to `master`:
Done when step 0 (raise the Pub/Sub ack deadline) — not done.**
**Branch:** reliability-hardening (not merged to `master` until after the demo; prod
deploys from `master`)
**Started:** 2026-10-05

## Goal

When anything fails in ingestion, media processing or rag orchestration, exactly one
error line appears, at the boundary where the work entered the service, with the full
stack trace and the `fileId` (or `conversationId`). HTTP callers get the right status
instead of a blanket 500. Each boundary is proven by a test that makes a dependency
throw.

## Not doing

- **Retries, backoff, dead-letter topics.** Kafka retries are turned *off* here, not
  tuned. Retry policy is the next ADR, written from what these logs show.
- **`FAILED` states and terminal pipeline events.** A file that fails is still stuck
  after this plan; the difference is that we can see why.
- **Timeouts on outbound calls** (ffprobe, Gemini, Groq). A hang is not an exception,
  so this plan can't see it. Separate work.
- **Jobs lost when the container restarts.** The media record is committed before the
  job runs (ADR-0014, What this costs). Needs job state in the database and a sweeper.
- **Idempotency** of the webhook and consumers.
- **Narrowing `spring.json.trusted.packages`.** Same property file as the
  deserializer change in brick 5, but a security change, not a failure-path one.
- **The per-job upload wait.** Has its own plan, `per-job-upload-wait.md`.
- **Full-context `@SpringBootTest` tests.** The three `contextLoads` tests stay
  disabled; they need GCS and AI credentials.
- **The gateway** beyond the login handler. It proxies; downstream statuses pass
  through.
- **`ChatController` and the old chat path behind it** (`ChatService`,
  `VertexAnswerGenerator`). Marked `@Deprecated`; nothing in the frontend calls them,
  and they will be deleted rather than fixed. Slice 3 leaves their 7 catches alone,
  and Done when step 6 skips them.
- **Finishing an answer after the user leaves.** Today a user who leaves mid-answer
  stops the agent and the half answer is not saved (brick 15 keeps that). Keeping the
  answer needs a separate stop request first: the Stop button works by closing the
  connection, so the server can't tell Stop from a closed tab. Its own plan, and an
  amendment to ADR-0014's SSE rule.
- **Signed URLs and ffmpeg output in INFO lines.** Media logs the signed GCS URL (a
  bearer token for 30 minutes) and every ffmpeg output line; ingestion logs the
  signed upload URL. Matters once logs leave the machine; part of the observability
  work.
- **Normalisation in its own service class.** `MediaProcessingService` is too large;
  this is a refactor, done after this plan.

## Decisions

- **Where failures go** — see ADR-0014 and its 2026-10-05 amendment.
- **Who writes tests** — Claude writes them, Varad reviews and runs them.
- **Test first, every brick.** Write the test that shows today's wrong behaviour, see
  it fail, then change the code and see it pass. A test that never failed proves
  nothing.
- **How failures are triggered** — Mockito fakes that throw, in tests. No
  fault-injection hooks in `src/main`. Real failures are what we watch for after
  merge, not something we trigger.
- **How "logged once" is checked** — capture the logback events themselves with a
  `ListAppender` on the handler's logger, and assert on their level, exception and MDC.
  Not printed output: a plain unit test's console pattern doesn't print MDC, so a check
  on the text can't see the `fileId`. The appender calls
  `prepareForDeferredProcessing()` on each event as it arrives, because logback reads
  an event's MDC lazily, from whichever thread asks first.
- **Kafka tests use `@EmbeddedKafka` with a small context**: the Kafka
  auto-configuration, our error handler, the listener under test, and fakes for its
  collaborators. Not a full app context, which needs GCS and Gemini. `spring-kafka-test`
  is already in media and rag; ingestion gets it in place of the unused
  `spring-rabbit-test`.
- **One wrapper for fire-and-forget tasks**, in `cortex-common`, doing both MDC and
  failure logging. It replaces `MdcUtils.withMDC`, so no thread can have one without
  the other.
- **The send helper waits on a `CompletableFuture`**, not on a `KafkaTemplate`, and
  lives in `cortex-common`'s `kafka` package next to `KafkaFailures`.
- **The producer decides when a send has failed, not the helper.** The Kafka producer
  already retries a send on its own for up to `delivery.timeout.ms` (2 minutes by
  default) before it reports failure. The helper waits longer than that (150 s), so
  its own timeout never fires first: with a shorter wait, a broker that's away for 15
  s would be reported as a failed send that then arrives anyway. A failed send
  therefore means the broker was unreachable for two minutes, not for a moment. Every
  send waits, progress events included: by then the next state-carrying send would
  fail too.
- **Kafka error handler:** Spring Kafka's `DefaultErrorHandler` with zero retries,
  wired as each service's `CommonErrorHandler` bean, and `ErrorHandlingDeserializer`
  in front of the JSON deserializer.
- **Report shared, policy per service.** `cortex-common` holds one stateless function,
  `KafkaFailures.reportAndSkip(fileIdOf)`: how a failed record is logged. Each service
  builds its own handler bean from it and Spring Kafka's parts, and keeps its policy
  there: retries, backoff, and later the dead-letter topic. Composition, not a base
  class; no beans or state in common. `spring-kafka` is an optional dependency of
  common, so the gateway doesn't get it.
- **HTTP error bodies are `ProblemDetail`** (RFC 9457), built into Spring 6. A standard
  shape rather than one we invent, and the one Spring MVC already uses for its own
  errors.
- **The gRPC boundary is a `GrpcExceptionHandler` bean**, Spring gRPC's hook, not a
  `catch` inside each service method.

## Open questions

None blocking Slices 1 and 2.

## Slices

### Slice 1 — every failure reaches one log line

**When this slice is done:** a failure in a fire-and-forget task, a Kafka listener or
a Kafka send produces one ERROR line with a stack trace and the `fileId`. Nothing
vanishes into a pool thread, and no failed chunk is retried nine times.

#### 1. Fire-and-forget wrapper in `cortex-common` — ✅ DONE 2026-10-05

The wrapper that replaces `withMDC`: copies MDC into the task, catches anything the
task throws, logs it once at ERROR with the stack trace, clears MDC. Unit tests only;
nothing calls it yet.

**DoD:** Tests in `cortex-common` pass: a task that throws produces exactly one ERROR
line, containing the exception's stack trace and the MDC `fileId` set by the caller.
A task that succeeds logs nothing. MDC is empty on the thread afterwards in both
cases. `mvn -pl cortex-common test` green.

#### 2. Media: the job and its threads run inside the wrapper — ✅ DONE 2026-10-08

`MediaEventConsumer` wraps the job in `BackgroundTask` where it hands it to the
executor, and the six `Thread.startVirtualThread(withMDC(...))` sites in
`MediaProcessingService` switch to it. The executor's MDC-copying task decorator in
`MediaProcessingConfig` goes (BackgroundTask carries MDC), and `withMDC` is deleted.
The catch-and-rethrow at the bottom of `processMedia` goes; the boundary does the
logging now.

Wrapped at the call site, not in the task decorator: `processMedia` throws checked
exceptions (`IOException`, `InterruptedException`), which a `Runnable` can't. So
`BackgroundTask.of` now takes a `Work` that may throw them, and restores the
interrupt flag when the failure is an `InterruptedException`.

**DoD:** `MediaJobBoundaryTest` hands a job to the real executor through the real
consumer, with `GcsStorageService` faked to throw on its first call. Before the
change: the one ERROR line comes from `MediaProcessingService` ("FFmpeg execution
failed", though ffmpeg never ran), and a `RuntimeException` rebuilt from a string,
cause dropped, escapes onto the pool thread. After it: one ERROR line, from
`BackgroundTask`, with the `fileId` and the original exception, and nothing escapes.
`BackgroundTaskTest` covers a checked exception and the interrupt flag. Both modules'
tests green, with `cortex-common` re-installed first.

#### 3. Rag: the agent threads run inside the wrapper — ✅ DONE 2026-10-08

The `Thread.startVirtualThread` site in `ConversationController` goes through a
helper, `SseEvents.answering`, which runs the answer inside `BackgroundTask`. The two
in `ChatController` stay as they are: that class is deprecated (see Not doing). A send failing because the client left now throws
`ClientDisconnectedException` (it was a bare `RuntimeException`) and is logged at INFO
(it stops the agent, as designed); anything else closes the emitter with the error and
reaches the wrapper. `ConversationController` puts `conversationId` in MDC before
starting the thread, and rag's log pattern prints it. The controllers' own log lines,
which carried the user's question text, are gone. The `SearchService` and
`OrchestrationService` futures are *not* wrapped: something waits on each (ADR-0014
amendment).

**DoD:** `ConversationAnswerBoundaryTest` drives the real controller with
`ConversationService` faked to throw. Before: the ERROR line comes from
`ConversationController`, with the question text and no `conversationId` in MDC. After:
one ERROR line from `BackgroundTask` with the `conversationId`. `SseEventsTest`:
finished → stream completed, no ERROR; failed → stream closed with the error, one
ERROR at the boundary; client gone → one INFO line, no ERROR.

#### 4. Kafka error handler in rag, proven with embedded Kafka — ✅ DONE 2026-10-09

The heavy one: first `@EmbeddedKafka` test in the repo. Error handler bean with zero
retries; `ErrorHandlingDeserializer` in front of the JSON deserializer.

**DoD:** `MediaChunkListenerBoundaryTest`. With `VisionService` faked to throw, a
`media-chunk-uploaded` record produces one ERROR line with its `fileId` and
`VisionService` is called **once**; a second record sent after it is consumed. A
record that isn't valid JSON produces one ERROR line and the record after it is
consumed.

Before the change, confirmed by running it: Gemini was called ten times, and Spring's
ERROR line carried no `fileId`. The non-JSON record was worse: it fails inside
`poll()`, the default handler can't skip it, and the consumer retried it in a tight
loop forever (dozens of identical errors per millisecond), never reaching the next
record. In production that happens to a test message sent from Redpanda Console, or
to any record left in the topic after its DTO class is renamed or moved.

#### 5. Kafka error handler in ingestion and media — ✅ DONE 2026-10-12

The report step moves from rag's config into `cortex-common` as
`KafkaFailures.reportAndSkip` (see Decisions), and rag's bean uses it. Ingestion and
media get the same bean, each with its own `fileId` lookup (`PipelineEventDTO`,
`FileIngestionEventDTO`), and `ErrorHandlingDeserializer`. Ingestion swaps
`spring-rabbit-test` for `spring-kafka-test`.

**DoD:** `KafkaFailuresTest` (no broker): one ERROR line with the `fileId` and the
record's topic, partition and offset; none for an unreadable record; a lookup that
throws still reports, and nothing escapes; the caller's MDC is left as it was.
Brick 4's `MediaChunkListenerBoundaryTest` passes unchanged on the refactored rag
bean. `PipelineEventsListenerBoundaryTest` in ingestion: a record whose save throws
gives one ERROR line with its `fileId`, one save attempt, and the next record is
consumed; a non-JSON record gives one ERROR line and the next record is consumed.
Media needs no listener test of its own: its listener only hands off (brick 2 covers
the job).

#### 6. Sends wait for the broker: the helper, ingestion and rag — ✅ DONE 2026-10-12

The helper in `cortex-common`, `KafkaSends.await(send)`, then the 7 sends in
`KafkaProducerService` and `OrchestrationService`.

**DoD:** `KafkaSendsTest`: a finished send returns its result; a failed one throws
the original exception, unwrapped; one with no answer throws after the wait (a short
one in the test); an interrupted wait puts the interrupt flag back. Ingestion: with
`KafkaTemplate` faked to fail, `KafkaProducerService.sendFileIngestedEvent` throws
(today it returns as if the send worked). Rag: with `KafkaTemplate` faked to fail
`EMBEDDING_COMPLETE`, `processMediaChunk` throws (today it returns), so brick 4's
boundary reports it.

**Risk this creates, fixed outside the code — ⬜ NOT DONE:** the upload webhook now
waits for its two sends, up to about two minutes while the broker is away. Pub/Sub
waits for the webhook's answer only as long as the push subscription's **ack
deadline** (10 s by default), then sends the notification again while the first
request is still waiting. Both succeed, so media gets "file ingested" twice and
processes the file twice (double ffmpeg work and Gemini/Groq cost). Before this
brick the webhook answered at once, so this couldn't happen. Fix: raise the push
subscription's ack deadline above 150 s (600 s is the maximum) in the GCP console.
Local runs can't show this: in development the webhook is called from Postman, not
by Pub/Sub. Listed as step 0 of Done when, so it isn't forgotten at merge.

#### 7. Sends wait for the broker: media — ✅ DONE 2026-10-13

The 13 sends in `MediaProcessingService`, through a private `sendPipelineEvent` as in
rag, plus `KafkaSends.await` on the `media-chunk-uploaded` send.

The normalisation thread's catch now covers only the work (ffmpeg, the upload,
setting `READY`), not its three sends. Before, a failed `NORMALISATION_COMPLETE`
send would have overwritten `READY` with `UNAVAILABLE` for a file that plays. Now a
failed send goes to the thread's boundary and leaves playback as it is (`PENDING` if
`NORMALISATION_STARTED` failed).

The upload worker's catch is unchanged: a failed send there is logged as "Upload
failed" and the chunk is marked `FAILED`. See Slice 3.

**DoD:** `MediaJobBoundaryTest`, with `KafkaTemplate` faked to fail every send. Before
the change: the job ignored the failed `PROBE_STARTED` send, ran ffprobe, and failed
there with four ERROR lines, none about Kafka. After it: one ERROR line, from the job's
boundary, with the `fileId` and the send's exception in its causes. Grep: every
`kafkaTemplate.send(` in `src/main` is inside `KafkaSends.await`.

### Slice 2 — HTTP and gRPC boundaries give the right answer

**When this slice is done:** a missing or not-yours file is a 404, bad input a 400,
anything else a 500 with a generic body; Pub/Sub retries only what can succeed later;
a failed user sync fails the login.

#### 8. Ingestion: one `@RestControllerAdvice` — ✅ DONE 2026-10-13 (browser check ⬜)

`ApiExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`, which already
answers `ResponseStatusException`, validation and the like with a `ProblemDetail`
(RFC 9457). Anything else is a 500 with a generic `detail`. Each failure is logged
once there: 4xx at WARN with the `detail`, 5xx at ERROR with the stack trace, with the
`fileId` from the path in MDC. A validation 400's `detail` is our DTO messages.

Not-found is `ResponseStatusException(NOT_FOUND, "File not found")`, the pattern
`signPlaybackUrl` and `subscribeToEvents` already used; no new exception type.
`FilesService` and the HTTP paths of `GcsStorageService` (`getPresignedURL`,
`signPlaybackUrl`) stop wrapping in `RuntimeException`. `handleFileUploadSuccess` is
the webhook's, left for brick 10. `server.error.include-message=always` removed.

Frontend: `request()` in `api.ts` reads `application/problem+json` and uses its
`detail`, then `title`. The rename, delete and upload dialogs already show the
error's message.

**DoD:** `FilesErrorResponsesTest`, standalone MockMvc with the real services and
faked repositories. Not `@WebMvcTest`: the application class enables JPA repositories,
so a slice would try to build them with no database. Deleting or renaming another
user's file is 404 with one WARN line carrying the `fileId`; a bad display name is 400
with the DTO's message as `detail`; a rejected file's playback keeps its 410; an
unexpected exception is a 500 whose body has no exception text, with one ERROR line.
All `application/problem+json`. Before the change, confirmed by running it: the three
`RuntimeException` paths escaped unhandled (a 500 in a running service, logged twice),
and the 400 and 410 had no body. In the browser, renaming a file to `a#b` shows
"Display name contains invalid characters" in the toast, not JSON.

#### 9. Rag: one `@RestControllerAdvice` — ✅ DONE 2026-10-13

A copy of ingestion's `ApiExceptionHandler`, in rag's `exception` package, not a shared
class. Two services, and the two already differ; extract when a third copy appears
or when observability changes both (rule of three). Each copy has its own test.

Rag's differences: the `{id}` path variable goes into MDC as `conversationId`; and a
failure inside an answer stream is answered here but not logged. It comes back
through `completeWithError` on an async dispatch, after `SseEvents.answering` has
logged it. Spring commits the stream on its **first event**, not when the emitter is
returned (found by running it). So an answer that fails before any event still gets a
real 500 `ProblemDetail`; after the first event nothing can be sent, and the stream
ends without `done`, which the frontend reads as "cut off". Rag's not-found was
already `ResponseStatusException`.

Frontend: the streaming call in `api.ts` reads problem bodies like `request()`.

**DoD:** `RagErrorResponsesTest`, standalone MockMvc as in brick 8, with the real
`ConversationService`. Another user's conversation is 404 with one WARN line carrying
the `conversationId`, also on the streaming endpoint, which asks for
`text/event-stream`; an empty question is 400 with the DTO's message; an unexpected
search failure is a generic 500 with one ERROR line; an answer that fails after its
first event, or before any, gives exactly one ERROR line, from `BackgroundTask` (the
second case also a 500 body). Before the change: the 404s and the 400 had no body,
the search failure escaped unhandled, and a failing answer's second dispatch escaped
too. With the handler but without the async rule, a failing answer logged twice. The
test requests send `Accept: application/json`, as the browser does: rag has an XML
converter, which standalone MockMvc, unlike Spring Boot, puts before the JSON one.

#### 10. Webhook: the status code tells Pub/Sub what to do — ✅

Unknown object or malformed body: 2xx and an ERROR line. Anything that may pass
(database or GCS failing): 5xx.

**DoD:** `@WebMvcTest` on `WebhookController`: unknown object name → 2xx; a body with
no object name → 2xx; storage faked to throw → 5xx. Each logs once.

**Done:** `WebhookResponsesTest`, standalone MockMvc for the same reason as brick 8,
with the real `GcsStorageService`. Five cases: no file for the object → 200; object no
longer in GCS → 200; no object name → 200; a body that is not JSON → 200; GCS failing
→ 500. Each logs one ERROR line. The 500 line is the advice's and carries the
`fileId`; MDC is empty after the request. Before: 500, 500, 400, 400, and the GCS
failure logged twice (the service's wrapping catch, then the advice) with no `fileId`.

`handleFileUploadSuccess` lost its wrapping catch. An object that can never be
processed throws `ResponseStatusException(NOT_FOUND)`, as in brick 8. A handler in
`WebhookController` turns that, and an unreadable body, into 200 and one ERROR line; it
wins over the advice, so the rest of the API keeps its 4xx. Everything else goes to the
advice: 500.

`MdcCleanupFilter` (new, ingestion) clears MDC when each request ends. Code below the
boundary now leaves `fileId` in MDC, so the advice's line still has it;
`GcsStorageService` no longer removes it. Later, tracing puts its ids in at the same
place.

#### 11. Login fails when the user sync fails — ✅

The first plan was to let the sync failure propagate out of
`OAuth2LoginSuccessHandler`. That is not enough: Spring saves the login to the
session **before** it calls the success handler, so the user would see an error page
and still be logged in, with no `users` row.

So the sync moved to the step that loads the user. `SyncingOidcUserService` (gateway;
Google login is OIDC) loads the user through Spring's `OidcUserService`, then makes
the same gRPC call. A failed call becomes `OAuth2AuthenticationException`
(`user_sync_failed`): Spring treats the login as failed, saves no session, and calls
`LoginFailureHandler`. That is the login's boundary: it logs once (ERROR with the
cause for a failed sync, WARN for anything else, such as the user cancelling at
Google) and redirects to the frontend with `?login=failed`, which shows a toast. The
stub is a bean (`GrpcClientConfig`). `OAuth2LoginSuccessHandler` is gone; its
redirect is `defaultSuccessUrl(..., true)`.

Ingestion: `GrpcCallExceptionHandler`, a `GrpcExceptionHandler` bean, is the gRPC
boundary. Spring gRPC's `GrpcExceptionHandlerInterceptor` calls it when a served call
throws; it logs once and answers `INTERNAL` with no internal text. `UserGrpcService`
has no `catch`.

**DoD:** `SyncingOidcUserServiceTest`: the stub throws → `user_sync_failed` with the
cause, nothing logged; the stub succeeds → the user's details were sent.
`LoginFailureHandlerTest`: both kinds of failure redirect to `?login=failed` with one
line at the right level. `UserGrpcBoundaryTest`: in-process server, the real
`UserGrpcService` behind Spring's interceptor; the repository throws → `INTERNAL`,
one ERROR line from the boundary; and component scanning installs the handler.

Before: the shells failed all six, and the ingestion ERROR line came from
`UserGrpcService`. After: green.

Browser check (user) ⬜: stop ingestion, log in → landing page with the toast; open
`/dashboard` → back to the landing page, not logged in.

Not doing: a deadline on the sync call (a hung ingestion hangs login) — "Timeouts on
outbound calls".

### Slice 3 — remove the catches below the boundaries

**When this slice is done:** every `catch` in `src/main` is a boundary handler,
re-throws with the cause, or has a comment on that line saying why carrying on is
correct. The deprecated chat path (`ChatController`, `ChatService`,
`VertexAnswerGenerator`) is left as it is (see Not doing).

**Planning pass (2026-10-15).** 62 catches. 17 already follow the rule
(`BackgroundTask`, `KafkaSends`, `KafkaFailures`, `SseEvents`, the gateway's, four in
`ConversationHistory`, `OrchestrationService`, `LibraryTools`); 4 more only need the
comment; 7 are in the deprecated chat path. The rest are below, by brick. Media first.

Decided with Varad: the deprecated path stays and is left out of the final grep; a
chunk upload keeps today's one retry in cleanup; the transcription retry loop stays
until the retry ADR.

The same rule applies to every catch removed here: if it was doing cleanup, the
cleanup moves to a `finally` before the catch goes.

#### 12. Media: one failure, one log line — ✅

Log hygiene; no job outcome changes. In `MediaProcessingService`, media
`GcsStorageService` and `MediaProcessingConfig`:

- The log-and-rethrow catches go: GCS `getPresignedUrl` and `uploadFile`, both
  ffprobe catches (a failed `PROBE_COMPLETE` send is inside both today, so it is
  logged twice as "FFprobe process failed"), `remuxFile`, `transcodeVideo`,
  `transcodeAudio`. The `log.error` before a `throw` goes too: metadata not found,
  ffmpeg's and ffprobe's exit codes. The methods declare their checked exceptions.
- `readHead` keeps one `catch (IOException)` that re-throws it as
  `UncheckedIOException`, no log: it runs inside a `BooleanSupplier`.
- `isAudioSilent`: an `IOException` is a WARN and the job carries on as "not silent"
  (a wasted transcription costs less than a lost transcript; the comment says so).
  An `InterruptedException` restores the flag and propagates; today it is swallowed.
- `monitorFFmpegOutput` loses its catch; its `BackgroundTask` logs it, with the
  `fileId`.
- The executor's reject handler restores the flag and throws
  `RejectedExecutionException` with the cause, so the Kafka handler reports the job
  that could not be queued, with its `fileId`. Today it is dropped with a line that
  has no id.
- Comments on the catches that stay: normalisation, `ClosedWatchServiceException`,
  the three per-file deletes.
- "Media file is corrupted": ERROR → WARN. A rejected upload is the user's input, not
  our failure.

**DoD:** `MediaJobBoundaryTest`: ffprobe fails → exactly one ERROR line, from the
job's boundary, with the `fileId`. Works with or without ffprobe installed, so it
runs in CI. Before: 4 lines with ffprobe installed, 2 without.

**Done:** the red run showed 4 ERROR lines for a failed probe (the exit code, "FFprobe
process failed" twice, the boundary), and the reject handler returning normally. A
second test covers that: both job threads busy, the queue full, the listener thread
interrupted → `consume` throws `RejectedExecutionException`, the flag is kept.
`isAudioSilent` still carries on as "not silent" on a `NumberFormatException` too, as
before, so no outcome changes. The normalisation catch also restores the interrupt
flag. Not covered by a test (they need real media): the remux and transcode catches,
`monitorFFmpegOutput`, `isAudioSilent`; checked by reading.

#### 13. Media: the helper threads hand their failures to the job — ✅

The watcher, the upload dispatcher and the upload workers are things the job waits
for, through cleanup. Today each logs and carries on, or stops silently, and the job
never hears:

- **A file can complete with a hole.** A failed send in the worker after
  `mediaChunkRepository.save` marks the chunk `FAILED`. Cleanup's retry then hits
  `uq_media_chunk_file_chunk` and fails again, cleanup logs a WARN and returns, and
  the job saves a `totalChunks` without that chunk.
- **A dead dispatcher hangs the job forever.** Its catch sets the interrupt flag and
  `break`s, with no log. Cleanup's drain loop then waits forever, logging "Waiting
  for N remaining uploads…" each second, and holds one of the two job threads.
- **A dead watcher kills a healthy job with the wrong cause.** The watcher updates
  the last-chunk time. Without it, after 3 minutes the stall check kills ffmpeg, and
  the job fails with "exit code 137".

Change:

- The watcher and the dispatcher run as `FutureTask`s, not inside `BackgroundTask`:
  the job waits for them, so ADR-0014 says they must not be wrapped. Each puts the
  `fileId` in its own MDC. Their own catches go, except the
  `ClosedWatchServiceException` one.
- The job calls `get()` on a finished one every second, in the ffmpeg wait loop and
  in cleanup's drain loop, and once before cleanup's verify; `get()` throws its
  failure into the job.
- The upload worker's catch stays, commented: it marks the chunk `FAILED`, records
  the cause in `MediaProcessingContext`, and logs a WARN, because cleanup tries it once
  more. The `// Retry logic` placeholder goes.
- Cleanup: a chunk still `FAILED` after its retry is a `throw`, with the first cause
  and the retry's as suppressed. Cleanup's outer catch-all goes. Its wait on the
  shared upload semaphore is not touched (that is `per-job-upload-wait.md`).
- ADR-0014 amendment: the watcher and the dispatcher move to the waited-for list.

| Case | Before | After |
|---|---|---|
| A chunk fails twice | File completes with a hole, one WARN | Job fails: one ERROR with the `fileId` and the first cause; no `totalChunks`, no `CHUNKING_COMPLETE` |
| A chunk fails once, then succeeds | Completes, one ERROR | Completes, one WARN |
| The dispatcher dies | Job hangs forever | Job fails within about a second, with the cause |
| The watcher dies | Its own ERROR line; if ffmpeg still runs 3 min after the last chunk the watcher saw, ffmpeg is killed and the job fails a second time, blaming ffmpeg's exit code | Job fails at once: one line, with the watcher's cause |

The 3 minutes are the stall check in `waitForFFmpegCompletion` (`stallTimeoutMS`); only
the watcher moves the last-chunk time while ffmpeg runs. A short file finishes first,
and cleanup's sweep uploads what the watcher missed.

Cost: uploads already running when the job fails (five at most) finish, and can still
save their row and send `media-chunk-uploaded`. Brick 14 stops new ones.

**Decided at the start of the brick:** test through the whole job with real ffmpeg,
skipped where ffmpeg is missing (as in CI), not by moving the upload code into its own
class first. Moving it stays an option with the split of `MediaProcessingService`.

**DoD:** a chunk failing twice fails the job with one ERROR line carrying the first
cause, and `updateChunkingResult` is never called; a chunk failing once still
completes; a dispatcher that dies fails the job instead of hanging (the test has a
time limit).

**Done:** `MediaJobUploadFailuresTest` runs the real job, ffprobe and ffmpeg included,
on a 130 s video it makes with ffmpeg (three 60 s chunk pairs), with GCS, Kafka and the
repositories faked. Two cases. Chunk 1's `media-chunk-uploaded` send fails after its
row is saved, and the retry's save hits the unique key: before, two ERROR lines "Upload
failed" and no job failure; after, two WARNs and one ERROR from the job's boundary,
cause `KafkaException`, the duplicate key suppressed, no `updateChunkingResult`, no
`CHUNKING_COMPLETE`. A GCS failure on one chunk's first upload: before, one ERROR line;
after, one WARN, and the file completes with all three chunks.

The dead dispatcher and the dead watcher are not tested: without a test hook in
`src/main` (Decisions) a test can't make them fail. Checked by reading: both are
`FutureTask`s, and the job calls `get()` on a finished one in the ffmpeg wait loop, in
cleanup's drain loop and before cleanup's verify, unwrapping `ExecutionException` as
`KafkaSends` does, so the job's line shows the helper's own exception. A helper that
fails after that last check is not reported: in practice only the watcher's
`WatchService.close()`, once every chunk is uploaded. The job has its result by then,
and failing it would stick a good file. The watcher's
"directory inaccessible" stop stays a WARN, not a failure: it also happens at a normal
end, when cleanup deletes `chunks/` while the watcher still runs. ADR-0014 amended
(2026-10-16).

First written with a failure slot in the context and a wrapper around
`BackgroundTask`; rewritten to `FutureTask` at review, because the wrapper caught
everything and left `BackgroundTask`'s logging dead.

#### 14. Media: cleanup in `finally` — ⬜

Today `processMedia` deletes its work directory only on the success path, so every
failed job leaves its files under `/tmp/media-processing-service-chunks`, and a job
that fails mid-chunking leaves ffmpeg running.

- A `finally` in `processMedia`, once the work directory exists: kill the ffmpeg
  chunking process if it is still running; empty the upload queue, so the dispatcher
  stops; join the normalisation thread; delete the whole work directory
  (`deleteRecursively` moves here from the success path).
- ffprobe and the volume-check ffmpeg are killed in a `finally` if still running.
  `runNormalization` kills its process when its wait is interrupted, not only on
  timeout.
- Normalisation deletes its own output file in a `finally`, not only on success.

Cost: a failed job waits for its normalisation, as a successful one already does,
and holds one of the two job threads meanwhile. Deleting the folder under a running
normalisation would break the playback file for nothing.

**DoD:** a job that fails after its work directory exists leaves no directory behind,
and no ffmpeg process. Same test question as brick 13: getting that far needs
ffprobe to succeed on a real file.

#### 15. Rag: a user who leaves stops the agent at once — ⬜

`AgentService.streamRound` wraps `parser.feed(piece)` in `catch (Exception e)`.
Segments are sent from inside the parser's callback, and `SegmentParser` wraps
everything in its `try`, the callback included. So a user leaving mid-answer is
caught as "streamed answer was not the expected JSON" (WARN), the model's stream is
read to the end of the round (tokens paid for, nobody listening), and the agent stops
only when the final uncited segment's send fails.

- `SegmentParser`: the `try` covers only `readValue`; the callback is outside it. A
  parse failure throws a new `SegmentParseException`.
- `streamRound`: the catch narrows to `SegmentParseException`. The model's stream is
  read through `toStream()` in try-with-resources instead of `toIterable()`, so
  leaving the loop cancels the request to Gemini, not only stops reading it (the test
  checks the cancel).
- `VertexAnswerGenerator` uses the same parser; its own catch-all still catches
  everything, so only its message changes.

What the user sees does not change: a half answer is never saved
(`ConversationService.ask`), and on return the question is marked unanswered, as
today.

**DoD:** `SegmentParserTest`: a callback that throws → the same exception comes out
of `feed`. A test with a fake chat model: the first send fails → the model's stream
is cancelled (`doOnCancel`), one INFO line, no WARN.

#### 16. Rag: pipeline catches, one line per failure — ⬜

Log hygiene; no outcome changes.

- The log-and-rethrow catches go: `VisionService`, `EmbeddingService`, rag
  `GcsStorageService` (both).
- `TranscriptionService`: the outer wrap goes. The retry loop stays, with a comment
  that the retry ADR decides it. The sleep's `InterruptedException` restores the
  flag before it propagates; today the flag is lost.
- Comments on the catches that already carry on correctly: `CitationNumberer`,
  `ConversationHistory.arguments`, `AgentService.parse`.

Before: a failed vision or embedding call is two ERROR lines (the service's, then
the Kafka report). After: one, the report, with the whole cause chain. This makes
Done when step 3 hold.

**DoD:** `MediaChunkListenerBoundaryTest` with the real `EmbeddingService` and rag
`GcsStorageService`, their repository and `Storage` faked to throw → one ERROR line,
from the report. `VisionService`'s Gemini client is hard to fake (`models` is a final
field); if a test can't reach it, its change is checked by reading, and the brick
says so.

#### 17. Ingestion: SSE to a user who left is INFO; the Kafka path reaches its boundary — ⬜

- `PipelineEventsService.processNewEvent`: the `JsonProcessingException` catch goes,
  so the Kafka handler reports it with the `fileId`. It also fixes a quiet case: a
  `PIPELINE_COMPLETE` that fails to serialise today leaves the file committed as
  `COMPLETED` with no event; after, the transaction rolls back.
- `sendPreviousEvents`: the catch goes. Spring buffers sends made before the endpoint
  returns, so a client can't leave here; the catch only hides unreadable stored
  metadata, which becomes a 500 at the advice.
- `SseEmitterRegistry.broadcast`: the catch stays (a user who left must not fail the
  Kafka record) but logs INFO, not ERROR with a stack trace, and says so on the line.
  ADR-0014's SSE rule.
- Comments: `completeEmitters`, the after-commit GCS delete in `FilesService`,
  `InternalJwtFilter` (common).
- Ingestion's "File too big": ERROR → WARN, as in brick 12.

Before: closing a file's page while it processes logs an ERROR with a stack trace
for every later event. After: one INFO.

**DoD:** an emitter whose send throws `IOException` → one INFO, no ERROR, and the
next broadcast doesn't call it. `PipelineEventsListenerBoundaryTest`: an
`ObjectMapper` that throws → reported once by the Kafka handler, with the `fileId`.

## Done when

0. **⬜ NOT DONE — before merging to `master`:** in the GCP console, raise the
   ack deadline of the Pub/Sub push subscription that calls the upload webhook to
   more than 150 s (600 s is the maximum). Without it, a short Kafka outage makes
   Pub/Sub send the same upload notification twice and the file is processed twice
   (see brick 6). Not checkable locally: in development the webhook is called from
   Postman. Tick this line when it's done.
1. `mvn -B verify` is green.
2. Locally (`docker compose up -d postgres redis kafka`, services on the dev profile),
   upload a short normal video. It completes, and no service logs an ERROR line.
3. Set a wrong Groq key in rag's dev config and upload again. Rag logs one ERROR line
   per audio chunk, each with the `fileId`. No record is redelivered by Kafka. (Groq
   still sees three attempts per chunk: `TranscriptionService` has its own retry
   loop, which stays until the retry ADR; brick 16.)
4. Stop the Kafka container while a file is chunking. Media logs one ERROR line with
   the `fileId`, from the job's boundary. Expect it to take one to two minutes: the
   producer waits up to `max.block.ms` (60 s) for topic metadata, and retries a send
   for up to `delivery.timeout.ms` (2 minutes) before it reports a failure.
   `KafkaSends` waits 150 s, so the producer's answer always comes first.
5. Ask for another user's file id (`DELETE /api/v1/files/<id>`) through the gateway.
   The answer is 404, not 500.
6. Grep `src/main` for `catch (`: every hit is a boundary handler, a re-throw, or has
   a comment on its line. `ChatController`, `ChatService` and `VertexAnswerGenerator`
   are skipped (deprecated; see Not doing).
