# Per-job upload wait

**Status:** Not started. Found while porting `media_processing_concurrency_bug.md`
on 2026-09-30. Nothing has been changed in code.
**Branch:** not created yet
**Started:** 2026-09-30

## Goal

When a media job finishes chunking, its cleanup waits for that job's own uploads and
nothing else. A second job uploading at the same time neither delays the first
job's cleanup nor gets its chunk folder deleted from under it.

## Not doing

- **Making `uploadSlots` per job.** The upload cap stays one limit for the whole
  container. See Decisions.
- **Retrying failed chunk uploads.** `uploadWorker` has a "Retry logic" placeholder
  in its catch block. Separate work.
- **Reworking the queue-drain step in cleanup.** The one-second sleep loop on the
  job's own queue is per job already and stays as it is.

## Decisions

- **Is the upload cap per job or per container?** Per container. What an upload
  costs is container-wide: the GCS `WriteChannel` buffers up to 16 MiB (default
  `chunkSize` in `google-cloud-storage` 2.63.0, read from the jar), plus a share of
  outbound bandwidth. A per-job cap would let two jobs run ten uploads. GCS itself
  sets no limit that five uploads come near. The reason the cap was first added
  isn't recorded; this is today's reasoning. If this gets challenged again (the
  2026-06-04 audit called the shared semaphore a bug), it should become an ADR.

## Open questions

None.

#### 1. Cleanup waits on a per-job in-flight count — ⬜

Add a count of the job's in-flight uploads to `MediaProcessingContext`. Cleanup
waits for that count to reach zero, instead of acquiring every permit of the shared
`uploadSlots` and releasing them again. `uploadSlots` itself stays as the
container-wide cap.

The count has to go up when the dispatcher takes a chunk off the queue, before it
blocks on `uploadSlots.acquire()`, and come down in the same `finally` that releases
the permit. Why not go up when the upload starts: a chunk that has been polled but is
still waiting for a permit is in neither the queue nor the count. Cleanup would see
an empty queue and zero in flight, go on to delete the `chunks/` folder, and the
upload would then fail on a missing file. The status map doesn't catch it either,
because the chunk is still `PENDING` and step 4 only counts `FAILED`. The same gap
exists today, through the shared semaphore, whenever cleanup gets its five permits
before a waiting dispatcher gets one.

**DoD:** Run two long uploads (5+ minutes each) at the same time against local
infra. In the logs, the first job's "Successfully cleaned up chunks directory" line
appears while the second job is still logging "Uploading chunk". Each file's
`chunks/` folder in GCS holds only its own chunks, and `media_chunk` rows for each
`fileId` point only at paths under that file's folder. Never more than five
"Uploading chunk" uploads are in flight at once across both jobs.

## Done when

1. Upload two 5-minute videos at the same time.
2. Both reach `CHUNKING_COMPLETE`, and the first one isn't held back by the second
   one's uploads.
3. In the bucket, each file's `chunks/` folder contains only its own chunks, and
   the counts match `total_chunks` on each file.
4. Upload a third file straight after; it processes normally.
