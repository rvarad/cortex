# Failure boundaries

**Status:** Not started. Plan written 2026-10-05 from ADR-0014. Slices 1 and 2 are
broken into bricks; Slice 3 gets its own planning pass once they land.
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

## Decisions

- **Where failures go** — see ADR-0014 and its 2026-10-05 amendment.
- **Who writes tests** — Claude writes them, Varad reviews and runs them.
- **Test first, every brick.** Write the test that shows today's wrong behaviour, see
  it fail, then change the code and see it pass. A test that never failed proves
  nothing.
- **How failures are triggered** — Mockito fakes that throw, in tests. No
  fault-injection hooks in `src/main`. Real failures are what we watch for after
  merge, not something we trigger.
- **How "logged once" is checked** — Spring Boot's `OutputCaptureExtension`: count the
  ERROR lines the test produced. Already on the classpath via
  `spring-boot-starter-test`.
- **Kafka tests use `@EmbeddedKafka` with a small context**: the Kafka
  auto-configuration, our error handler, the listener under test, and fakes for its
  collaborators. Not a full app context, which needs GCS and Gemini. `spring-kafka-test`
  is already in media and rag; ingestion gets it in place of the unused
  `spring-rabbit-test`.
- **One wrapper for fire-and-forget tasks**, in `cortex-common`, doing both MDC and
  failure logging. It replaces `MdcUtils.withMDC`, so no thread can have one without
  the other.
- **The send helper waits on a `CompletableFuture`**, not on a `KafkaTemplate`, so it
  lives in `cortex-common` without adding Kafka to that module. Timeout: 10 seconds.
- **Kafka error handler:** Spring Kafka's `DefaultErrorHandler` with zero retries,
  wired as each service's `CommonErrorHandler` bean, and `ErrorHandlingDeserializer`
  in front of the JSON deserializer.
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

#### 1. Fire-and-forget wrapper in `cortex-common` — ⬜

The wrapper that replaces `withMDC`: copies MDC into the task, catches anything the
task throws, logs it once at ERROR with the stack trace, clears MDC. Unit tests only;
nothing calls it yet.

**DoD:** Tests in `cortex-common` pass: a task that throws produces exactly one ERROR
line, containing the exception's stack trace and the MDC `fileId` set by the caller.
A task that succeeds logs nothing. MDC is empty on the thread afterwards in both
cases. `mvn -pl cortex-common test` green.

#### 2. Media: the job and its threads run inside the wrapper — ⬜

The executor's task decorator in `MediaProcessingCofig` and the five
`Thread.startVirtualThread(withMDC(...))` sites in `MediaProcessingService` switch to
the wrapper. `withMDC` is deleted. The catch-and-rethrow at the bottom of
`processMedia` goes; the boundary does the logging now.

**DoD:** A test hands a media job to the real executor with `GcsStorageService`
faked to throw on its first call. Before the change, the log has no ERROR line from
our code (the failure goes to stderr). After it: one ERROR line with the `fileId`
and the fake's exception. `mvn -pl cortex-media-processing-service -am test` green.

#### 3. Rag: the agent threads run inside the wrapper — ⬜

The three `Thread.startVirtualThread` sites in `ChatController` and
`ConversationController`. A send failing because the client left is logged at INFO
(it stops the agent, as designed); anything else is completed on the emitter and
reaches the wrapper. The `SearchService` and `OrchestrationService` futures are *not*
wrapped: something waits on each (ADR-0014 amendment).

**DoD:** Test with `ConversationService` faked to throw: one ERROR line with the
`conversationId`, and the emitter completes with an error. Test with a fake emitter
whose `send` throws `IOException`: an INFO line, no ERROR.

#### 4. Kafka error handler in rag, proven with embedded Kafka — ⬜

The heavy one: first `@EmbeddedKafka` test in the repo. Error handler bean with zero
retries; `ErrorHandlingDeserializer` in front of the JSON deserializer.

**DoD:** With `VisionService` faked to throw, a `media-chunk-uploaded` record
produces one ERROR line and `VisionService` is called **once**. Before the change,
the same test shows ten calls. A second record sent after the failing one is
consumed. A record that isn't valid JSON produces one ERROR line and the record
after it is consumed.

#### 5. Kafka error handler in ingestion and media — ⬜

Same configuration in both. Ingestion swaps `spring-rabbit-test` for
`spring-kafka-test`.

**DoD:** Ingestion test: a `cortex.pipeline.events` record whose save throws
produces one ERROR line, one save attempt, and the next record is consumed. Media
needs no listener test of its own: its listener only hands off (brick 2 covers the
job).

#### 6. Sends wait for the broker: the helper, ingestion and rag — ⬜

The helper in `cortex-common`, then the 7 sends in `KafkaProducerService` and
`OrchestrationService`.

**DoD:** Unit test: a send whose future completes exceptionally makes the helper
throw with the original cause; one that never completes throws after the timeout.
Rag test: with `KafkaTemplate` faked to return a failed future for
`EMBEDDING_COMPLETE`, the listener's boundary logs one ERROR line.

#### 7. Sends wait for the broker: media — ⬜

The 13 sends in `MediaProcessingService`.

**DoD:** With `KafkaTemplate` faked to fail on `CHUNKING_STARTED`, the job's
boundary logs one ERROR line and ffmpeg is never started. Grep: no
`kafkaTemplate.send(` in `src/main` whose result is dropped.

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

**Cleanup goes in `finally`, not `catch`.** Letting a failure propagate must not skip
cleanup. Today `processMedia` deletes its work directory only on the success path,
so every failed job leaves its files under `/tmp/media-processing-service-chunks`.
That deletion moves into a `finally`. The same check applies to every catch removed
in this slice: if it was doing cleanup, the cleanup moves to a `finally` before the
catch goes.

## Done when

1. `mvn -B verify` is green.
2. Locally (`docker compose up -d postgres redis kafka`, services on the dev profile),
   upload a short normal video. It completes, and no service logs an ERROR line.
3. Set a wrong Groq key in rag's dev config and upload again. Rag logs one ERROR line
   per audio chunk, each with the `fileId`. No record is redelivered by Kafka. (Groq
   still sees three attempts per chunk: `TranscriptionService` has its own retry
   loop, which Slice 3 looks at.)
4. Stop the Kafka container while a file is chunking. Media logs one ERROR line with
   the `fileId`, from the job's boundary. Expect it to take up to a minute: the
   producer blocks for `max.block.ms` (60 s by default) before a send fails, which
   is before the helper's 10-second wait even starts.
5. Ask for another user's file id (`DELETE /api/v1/files/<id>`) through the gateway.
   The answer is 404, not 500.
6. Grep `src/main` for `catch (`: every hit is a boundary handler, a re-throw, or has
   a comment on its line.
