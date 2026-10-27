# 0014. Exceptions propagate to a named boundary, and only the boundary handles them

**Status:** Amended 2026-10-16 (accepted 2026-10-05)
**Verified by:** Read of every `src/main` file on `reliability-hardening`, 2026-10-04.
Counted 63 `catch` blocks, 37 `new RuntimeException(...)` wraps, and 20
`kafkaTemplate.send(...)` calls, none of whose results is looked at. Nothing has been
run yet; that is what this ADR is for.

## The question

When something fails inside a service, where does the failure go, who decides what
happens next, and how does anyone find out it happened?

Today there is no answer. Failures end in one of three places, picked call site by
call site:

- **Caught, logged, carried on.** A failed chunk upload, a failed gRPC user sync, a
  failed historical SSE send. The log has it; the system behaves as if it succeeded.
- **Thrown into nothing.** `processMedia` runs on a thread pool and throws into the
  pool thread, where it is printed to stderr and dropped. The `@KafkaListener`
  methods have no error handler, so Spring Kafka's default retries immediately nine
  times and then skips the record.
- **Wrapped and re-thrown as `RuntimeException`.** A missing file and a database
  outage reach the caller as the same 500.

And a fourth that leaves no trace at all: a Kafka send that fails is never seen,
because no caller looks at the future `send` returns.

Nobody can say what fails in production, because most failures don't reach a place
where they would be seen.

## Decision

**A boundary is where work enters a service.** There are five kinds, and only these:

1. **HTTP request** — a controller method, including the Pub/Sub webhook and the
   OAuth login success handler.
2. **Kafka record** — a `@KafkaListener` method.
3. **Background task we start** — the top of every `Runnable`/`Supplier` handed to a
   thread, executor, virtual thread or `CompletableFuture`: the media job, the
   normalisation thread, the watcher, the upload dispatcher and workers, the agent
   threads behind each SSE stream.
4. **gRPC call served** — `UserGrpcService`.
5. **SSE stream** — the point where an event is written to a client that may already
   have left.

**Inside a boundary, code does not handle failures. It lets them propagate.** Calls
out — GCS, Kafka, Postgres, Gemini, Groq, ffmpeg/ffprobe, gRPC — are not boundaries.
Their failures travel up to the boundary that started the work.

**Each boundary has one handler per service, and it is the only place a failure is
logged as an error.** It logs once, with the full stack trace and the ids in MDC
(`fileId`, `chunkId`, `conversationId`, `userId` where they exist), and then does the
boundary's one job:

- **HTTP** — one `@RestControllerAdvice` per service maps known failures to their
  status (not found → 404, invalid → 400, conflict → 409) and everything else to a
  500 with a generic body. Internal messages stay in the log, not the response.
- **Webhook** — the status code is an instruction to Pub/Sub: 2xx means "done",
  anything else means "send it again". A message we can never process (unknown
  object, malformed body) gets a 2xx and an error log. A failure that may pass
  (database down, GCS down) gets a 5xx.
- **Kafka** — one error handler per service, set to **no retries for now**: log,
  then skip the record. Deserialization failures go through the same handler,
  instead of looping on a record that can never be read.
- **Background task** — catches everything at the top of the task and logs it. No
  thread ends without a log line saying why.
- **gRPC** — logs, then returns a status to the caller.
- **SSE** — a send failing because the client left is expected: logged at INFO and
  used to stop the work, as `SseEvents` already does. Any other failure goes to the
  background-task handler of the thread running the stream.

**A media job is a background task, not a Kafka record.** `MediaEventConsumer` hands
the job to `mediaProcessingExecutor` and returns. That is deliberate: a job takes
15 to 40 minutes, and a listener that blocks that long misses
`max.poll.interval.ms`, so Kafka evicts the consumer and redelivers the record. The
Kafka boundary there covers only the handoff. Everything `processMedia` does belongs to
the background-task boundary at the top of the job.

**Every Kafka send waits for the broker's acknowledgement**, with a timeout of a
few seconds, so a failed send throws at the call site and propagates like any other
failure. This is the producer's `send` future, which resolves in milliseconds. It
never means a listener waits for the work its record started.

**What this ADR does not decide:** retries, backoff, dead-letter topics, `FAILED`
states, timeouts on outbound calls, or the sweeper. Those depend on which failures
actually happen, and this ADR is how we find out. Each gets its own decision later.

## Why

**You cannot fix failures you cannot see.** Reading the code gives guesses about what
fails in production: Gemini 429s, ffprobe hangs, chunks that never pair. None of it
has been observed, because the failures stop at 63 different places before reaching
a log line anyone reads, or reach no log at all. Propagating to a few named places
turns "it's stuck, no idea why" into one error line with a stack trace and a
`fileId`.

**One decision per boundary, not per call site.** A call site three levels down
doesn't know whether it's inside an HTTP request, a Kafka record or a media job, so
it can't know what the right reaction is. The boundary knows. Every `catch` that
logs and carries on today is a call site guessing.

**No retries yet, on purpose.** Since `OrchestrationService` stopped swallowing its
exception, a failed chunk goes to Spring Kafka's default handler: nine immediate
retries, no wait between them, each calling Gemini and Groq again, then the record
is dropped anyway. That is up to 10× the AI spend for the same stuck file. Turning
retries off makes behaviour no worse than today (the file was stuck before too) and
stops the extra spend. Retries come back once we know which failures pass on their
own.

**Awaiting sends is what "propagate" means for Kafka.** A send nobody waits on is a
failure with no stack, no log and no caller: the worst kind. Waiting costs a broker
round trip per send. At one box and a handful of files, that is milliseconds against
jobs that take minutes.

## Alternatives rejected

- **Add logging to each existing `catch`.** Keeps 63 separate decisions about what
  failure means, and logs most failures twice or three times as they're wrapped and
  re-thrown. The volume would also push real errors out of the 30 MB log rotation
  faster.
- **Turn on retries and dead-letter topics now.** Sets
  retry counts and backoff before we know whether failures are rate limits, timeouts
  or bad input. Each needs a different answer, and retrying bad input only costs
  money.
- **Attach a logging callback to each Kafka send instead of waiting.** The failure
  gets logged but still doesn't reach the boundary, so the work carries on as if the
  event went out. A `CHUNKING_COMPLETE` that never arrives leaves the file stuck
  exactly as before, just with a log line.

## What this costs

- **More things fail visibly.** Code that used to carry on past a failure will now
  stop. Example: an upload worker that used to log and continue now fails the job.
  Some of those failures were hiding problems. Some were harmless and will need an
  explicit, commented "this may fail" at the call site.
- **A dropped Kafka record stays dropped.** With retries off, a chunk that fails once
  is not retried. Today it is retried nine times, and the file is stuck either way.
  Until the retry ADR, a transient Gemini error costs that chunk. The difference is
  that we see it.
- **Waiting for acknowledgements slows the media path** by one broker round trip per
  event, about six events per chunk. The wait happens on the job's own threads,
  never on a listener thread.
- **The media record is committed before the job runs.** Because of the handoff, a
  job lost to a restart or crash is not redelivered. This ADR makes the loss visible
  only when the job fails while running; a job killed with the container leaves no
  log. That needs its own decision (job state in the database, a sweeper), not a
  listener that waits.
- **Changing the login handler changes user-visible behaviour.** Today a failed user
  sync is logged and login succeeds, then the first conversation fails on the
  `users` foreign key (see V10's comment, which assumes the opposite). Under this
  ADR the failure propagates and login fails. That matches what V10 already expects,
  but it means an ingestion outage blocks login.

## Invariants this creates

- Each JPA/Kafka service has exactly one `@RestControllerAdvice` and one Kafka error
  handler bean.
- Every `catch` in `src/main` does one of three things: it is a boundary handler, it
  re-throws (keeping the cause), or it carries a comment on that line saying why
  carrying on is correct without the result. A bare log-and-continue is a bug.
- A failure is logged as an error once, at the boundary. Code below a boundary does
  not log and re-throw.
- No `kafkaTemplate.send(...)` result is dropped.
- No listener waits for long-running work. `MediaEventConsumer` hands off and
  returns; a reader may think it should wait for the job. It must not.
- No exception text is concatenated into a message string (`"..." + e`). The cause
  is passed as the cause.
- `catch (InterruptedException e)` restores the interrupt flag before anything else.
- `server.error.include-message` is not `always` in any service.
- Every task handed to a thread starts inside the background-task handler, the same
  way every task already starts inside `withMDC`.

## What would change our mind

This is a deferral for retries and terminal states. Write those ADRs when the
boundary logs show, for each Kafka boundary, which exception types occur and roughly
how often: enough to tell transient failures (429, timeout, connection reset) from
permanent ones (bad input, missing row, unsupported media). Run each pipeline failure
path five times before deciding (AGENTS.md: one run is not evidence).

## Amendments

### 2026-10-05 — A task is a boundary only if nothing waits for its result

The Decision lists every `CompletableFuture` task as a background-task boundary. That
is too broad. Listing every place work moves to another thread showed two kinds:

- **Tasks whose result is waited for.** The vision, transcription and embedding
  futures in `OrchestrationService`, and the semantic and lexical search futures in
  `SearchService`. A failure there comes back through `get()` or `join()` to the
  waiting code, and travels on to the Kafka or HTTP boundary above it. These are not
  boundaries. Wrapping them would log the same failure twice.
- **Fire-and-forget tasks.** The media job, the normalisation thread, the watcher, the
  upload dispatcher and upload workers, the two ffmpeg output readers, and the agent
  threads in `ChatController` and `ConversationController`. When one fails, nothing
  above it ever hears about it. These are the background-task boundaries.

The normalisation thread is the one to watch. `processMedia` calls
`normalisationThread.join()`, which looks like waiting for a result, but
`Thread.join()` never passes on the thread's exception. It is fire-and-forget, and
it is a boundary.

The invariant "Every task handed to a thread starts inside the background-task
handler" now reads: every **fire-and-forget** task does. A task whose result is
waited for must not be wrapped.

### 2026-10-12 — The Kafka report step is shared; the policy stays in each service

The Decision gives each service one Kafka error handler. That still holds: each service
declares its own `CommonErrorHandler` bean. What changed is where the report step comes
from.

A Kafka error handler has three parts: how a failed record is reported, what is retried
and how often, and what happens after the last attempt (skip now, a dead-letter topic
later). Only the first is the same in every service. It moves to `cortex-common` as one
stateless function, `KafkaFailures.reportAndSkip(fileIdOf)`. Each service builds its
handler from that function and Spring Kafka's own parts, and passes only how to find
the `fileId` in its own DTO.

Why shared: the report step holds two traps, and three copies would each have to get
both right. It must restore the listener thread's MDC, or the next record's log lines
carry the wrong `fileId`. And it must never throw, because a recoverer that throws
makes Spring Kafka deliver the record again, forever — the same endless loop an
unreadable record caused before `ErrorHandlingDeserializer` was added.
`KafkaFailuresTest` covers both once.

Why the rest stays in each service: the right retry policy depends on the failure, and
only the service knows its failures (Gemini rate limits in rag, a database outage in
ingestion). One central handler would need a branch per service and would grow with
every failure case. Composition, not a base class: a service reads its whole policy in
its own config.

`spring-kafka` is an `optional` dependency of `cortex-common`, so it does not reach the
gateway, which has no Kafka.

New invariants:
- `cortex-common` holds no Kafka beans, no `@Configuration`, and no policy: no
  backoff, no retry rules, no dead-letter topics. Only how a failed record is
  reported.
- Every service's Kafka error handler reports through `KafkaFailures`.

### 2026-10-16 — The watcher and the upload dispatcher are waited for

The 2026-10-05 amendment lists the watcher and the upload dispatcher as fire-and-forget.
They are not: the job waits for them, through cleanup, before it saves `totalChunks`.
As boundaries, each logged its own failure and the job never heard: a dead dispatcher
left cleanup waiting forever, and a dead watcher let the stall check kill a healthy
ffmpeg, so the job failed blaming ffmpeg.

They move to the first list. Each runs as a `FutureTask`, and the job calls `get()` on
a finished one while it waits (the ffmpeg wait loop, cleanup's drain loop, before
cleanup's verify), so a failure comes back to the job and is logged once, at its
boundary.

The upload workers stay fire-and-forget, but a worker's failure no longer ends there:
it is recorded on its chunk and logged as a WARN, and cleanup tries the chunk once
more. A chunk that fails again fails the job, with the first failure as the cause.
Before, a chunk that failed twice left a hole in a file that then completed.
