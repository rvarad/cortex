# Failure boundaries

**Status:** Slice 1 done (bricks 1–7). Next is Slice 2, brick 8.
Every media thread, the media job, and each conversation answer run inside
`BackgroundTask`; every Kafka listener reports a failed or unreadable record once,
through the shared `KafkaFailures` report, and moves on; every Kafka send waits for
the broker. **Before merging to `master`:
Done when step 0 (raise the Pub/Sub ack deadline) — not done.** Slice 3 gets its own
planning pass once Slices 1 and 2 land.
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
  and they will be deleted rather than fixed.
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

#### 8. Ingestion: one `@RestControllerAdvice` — ⬜

A not-found exception type. `FilesService` and `GcsStorageService` stop wrapping in
`RuntimeException`. `server.error.include-message=always` removed. Error bodies are
Spring's `ProblemDetail` (RFC 9457), the same shape Spring MVC uses for its own
errors when `spring.mvc.problemdetails.enabled` is on, so every error from the
service has one shape.

Frontend: `request()` in `api.ts` puts the raw response body into `ApiError`'s
message, and the delete, rename and upload dialogs show that message to the user
as-is. It learns to read `application/problem+json` and use its `detail`, falling
back to `title`.

**DoD:** `@WebMvcTest` on `FilesController`: deleting or renaming another user's file
is 404 (today it's 500); an unexpected exception is a 500 whose body contains no
exception text; each logs once. Both bodies are `application/problem+json` with
`status` and `title` set. In the browser, a failed rename shows a sentence in the
toast, not JSON.

#### 9. Rag: one `@RestControllerAdvice` — ⬜

Same for search, chats and conversations, with `ProblemDetail` bodies as in brick 8.
The streaming endpoints are the exception: once the 200 and the first event have
gone out, a failure can't change the status. Those failures end the stream without
`done`, which the frontend already reads as "cut off".

**DoD:** `@WebMvcTest` on `ConversationController` and `SearchController`: another
user's conversation is 404, an unexpected exception is a generic 500, each logged
once, both as `application/problem+json`.

#### 10. Webhook: the status code tells Pub/Sub what to do — ⬜

Unknown object or malformed body: 2xx and an ERROR line. Anything that may pass
(database or GCS failing): 5xx.

**DoD:** `@WebMvcTest` on `WebhookController`: unknown object name → 2xx; a body with
no object name → 2xx; storage faked to throw → 5xx. Each logs once.

#### 11. Login fails when the user sync fails — ⬜

`OAuth2LoginSuccessHandler` gets its gRPC stub injected (today it builds one in its
constructor, which a test can't replace) and lets a sync failure propagate.

On the ingestion side, the gRPC boundary handler is a `GrpcExceptionHandler` bean,
Spring gRPC's hook for failures in served calls (in `spring-grpc-core` 0.12.0).
`UserGrpcService` stops catching and calling `onError` itself, which is a hand-built
copy of that hook.

**DoD:** Unit test: stub faked to throw → the handler throws and does not redirect to
the dashboard. Today the same test shows a redirect. Test on the ingestion side:
`UserRepository` faked to throw → the caller gets `INTERNAL`, one ERROR line, and
`UserGrpcService` has no `catch`.

### Slice 3 — remove the catches below the boundaries

**When this slice is done:** every `catch` in `src/main` is a boundary handler,
re-throws with the cause, or has a comment on that line saying why carrying on is
correct.

Not broken into bricks yet. Each catch needs its own look, and Slices 1–2 will
remove some and change what others mean. Expected shape, by service and size:
media (`MediaProcessingService` 18, `GcsStorageService` 3), where the upload-worker
catch changes behaviour and gets a brick to itself; rag (27 across 15 files); ingestion
(12). Also: `"..." + e` messages, and `catch (InterruptedException e)` that doesn't
restore the interrupt flag.

**The upload dispatcher can hang a job forever.** Its catch in
`startUploadDispatcher` logs nothing: it sets the interrupt flag and `break`s, so the
dispatcher stops. Nothing drains the upload queue after that, and step 2 of
`cleanUpWorkingDir` (`while` the queue isn't empty, sleep one second) waits forever,
logging "Waiting for N remaining uploads…" each second. The job never finishes and
keeps one of the two job threads. Rare today (it needs an interrupt, and nothing
sends one), but a hang, not a failure, so no boundary ever sees it. Fix it with this
slice's media bricks, and give the drain loop a deadline when the timeouts work
lands.

**A client leaving mid-answer is mistaken for bad JSON.** In `AgentService.streamRound`,
the `catch (Exception e)` around `parser.feed(piece)` also catches the
`ClientDisconnectedException` thrown when a segment's send fails, because segments are
sent from inside the parser's callback. It logs "streamed answer was not the expected
JSON" at WARN, keeps reading the model's stream to the end of the round (tokens paid
for, nobody listening), and only stops when the final uncited segment's send fails.
That catch must let the disconnect through.

**A chunk whose send fails can leave a hole in the file.** The upload worker's catch
also catches a failed send (brick 7): it logs "Upload failed" and marks the chunk
`FAILED`. Cleanup tries each `FAILED` chunk once more. If the send that failed came
after `mediaChunkRepository.save` (`MEDIA_CHUNK_READY` or `media-chunk-uploaded`), the
second try hits `uq_media_chunk_file_chunk` and fails again, even with the broker back.
Cleanup then only logs a WARN, and the job saves a `totalChunks` without that chunk,
so the file completes with a hole. ADR-0014's target: the worker hands its failure to
the job, and the job fails. The `catch` in `cleanUpWorkingDir` must let that through.

**ffprobe's catches wrap a failed `PROBE_COMPLETE` send.** That send is inside both
catches in `probeMediaFile`, so its failure is logged twice as "FFprobe process
failed" before it reaches the boundary.

**Cleanup goes in `finally`, not `catch`.** Letting a failure propagate must not skip
cleanup. Today `processMedia` deletes its work directory only on the success path,
so every failed job leaves its files under `/tmp/media-processing-service-chunks`.
That deletion moves into a `finally`. The same check applies to every catch removed
in this slice: if it was doing cleanup, the cleanup moves to a `finally` before the
catch goes.

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
   loop, which Slice 3 looks at.)
4. Stop the Kafka container while a file is chunking. Media logs one ERROR line with
   the `fileId`, from the job's boundary. Expect it to take one to two minutes: the
   producer waits up to `max.block.ms` (60 s) for topic metadata, and retries a send
   for up to `delivery.timeout.ms` (2 minutes) before it reports a failure.
   `KafkaSends` waits 150 s, so the producer's answer always comes first.
5. Ask for another user's file id (`DELETE /api/v1/files/<id>`) through the gateway.
   The answer is 404, not 500.
6. Grep `src/main` for `catch (`: every hit is a boundary handler, a re-throw, or has
   a comment on its line.
