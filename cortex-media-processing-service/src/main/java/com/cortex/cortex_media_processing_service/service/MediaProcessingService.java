package com.cortex.cortex_media_processing_service.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.MediaFileManifestDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

  private enum UploadStatus {
    PENDING,
    IN_PROGRESS,
    UPLOADED,
    FAILED
  }

  private final GcsStorageService gcsStorageService;

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @Value("${app.kafka.topic.pipeline-events}")
  private String pipelineEventsTopic;

  // private final TranscriptionService transcriptionService;

  private static final int MAX_CONCURRENT_UPLOADS = 5;

  private static final int AUDIO_CHUNK_DURATION_S = 60;
  private static final int FFMPEG_PROCESSING_TIMEOUT_M = 30;

  private final AtomicLong lastChunkTimeMS = new AtomicLong(System.currentTimeMillis());

  private final AtomicInteger totalChunks = new AtomicInteger(0);

  private static final String TEMP_DIRECTORY = "/tmp/media-processing-service-chunks";

  AtomicBoolean isRunning = new AtomicBoolean(false);

  private final Map<Integer, ChunkPair> chunkPairMap = new ConcurrentHashMap<>();

  private final Map<String, UploadStatus> chunkRegistry = new ConcurrentHashMap<>();

  private final BlockingQueue<Path> uploadQueue = new LinkedBlockingQueue<>();

  private final Semaphore uploadSlots = new Semaphore(MAX_CONCURRENT_UPLOADS);

  @Data
  private static class ChunkPair {
    private String videoPath;
    private String audioPath;
    private double start_s;
    private double end_s;

    public boolean isComplete(MediaFileManifestDTO manifestDTO) {
      boolean videoSatisfied = !manifestDTO.isHasVideo() || videoPath != null;
      boolean audioSatisfied = !manifestDTO.isHasAudio() || audioPath != null;

      return videoSatisfied && audioSatisfied;
    }
  }

  public void processMedia(String objectName, UUID fileId) {
    log.info("Beginning chunking for file: {}", objectName);

    isRunning.set(true);
    chunkRegistry.clear();
    uploadQueue.clear();
    chunkPairMap.clear();

    try {
      String streamUrl = gcsStorageService.getPresignedUrl(objectName);

      MediaFileManifestDTO manifestDTO = probeMediaFile(streamUrl);
      if (manifestDTO.isCorrupted()) {
        log.error("Media file is corrupted: {}", objectName);
        throw new RuntimeException("Media file is corrupted: " + objectName);
      }

      Path workDir = createWorkingDir(objectName);
      log.info("Created working directory: {}", workDir.toString());
      log.info("Created working directory: {}", workDir.getFileName().toString());

      String videoPattern = workDir.resolve("video_chunk_%03d.mp4").toString();
      String audioPattern = workDir.resolve("audio_chunk_%03d.wav").toString();

      Thread.startVirtualThread(() -> startDirectoryWatcher(workDir));
      Thread.startVirtualThread(() -> startUploadDispatcher(objectName, fileId, manifestDTO));

      lastChunkTimeMS.set(System.currentTimeMillis());

      totalChunks.set(0);

      PipelineEventDTO pipelineEventDTO = PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNKING_STARTED).message("Chunking started").build();
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), pipelineEventDTO);

      Process ffmpegProcess = startFFmpegProcess(streamUrl, videoPattern, audioPattern);

      Thread.startVirtualThread(() -> monitorFFmpegOutput(ffmpegProcess));

      waitForFFmpegCompletion(ffmpegProcess);

      int exitCode = ffmpegProcess.exitValue();
      if (exitCode != 0) {
        log.error("FFmpeg process failed with exit code: {}", exitCode);
        throw new Exception("FFmpeg process failed with exit code: " + exitCode);
      }

      log.info("FFmpeg process completed successfully");

      cleanUpWorkingDir(workDir, objectName);

      isRunning.set(false);

      int totalChunksCount = totalChunks.get();

      FileMetadata fileMetadata = fileMetadataRepository.findById(fileId).orElse(null);

      if (fileMetadata != null) {
        fileMetadata.setTotalChunks(totalChunksCount);
        fileMetadata.setFileStatus(FileStatusEnum.CHUNKED);
        fileMetadataRepository.save(fileMetadata);

        log.info("[MediaProcessingService] Set totalChunks = {} for fileId: {}", totalChunksCount, fileId);
      }

      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
          PipelineEventDTO.builder().fileId(fileId).eventType(PipelineEventEnum.CHUNKING_COMPLETE)
              .message("Media chunking finished successfully").metadata(Map.of("totalChunks", totalChunksCount))
              .build());

      log.info("Media processing completed and cleaned up.");

    } catch (Exception e) {
      log.error("FFmpeg execution failed", e);
      isRunning.set(false); // Ensure we signal shutdown even on error
      throw new RuntimeException("Failed to process media file: " + objectName + " exception: " + e);
    }
  }

  private MediaFileManifestDTO probeMediaFile(String streamUrl) {
    log.info("Probing media file: {}", streamUrl);

    try {
      ProcessBuilder processBuilder = new ProcessBuilder("ffprobe", "-v", "error",
          "-show_entries", "format=duration:stream=codec_type",
          "-of", "csv=p=0",
          streamUrl);
      processBuilder.redirectErrorStream(true);

      Process process = processBuilder.start();

      boolean video = false;
      boolean audio = false;

      double duration_s = 0.0;

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty())
            continue;

          log.info("FFprobe output: {}", line);
          if (line.contains("video")) {
            video = true;
          } else if (line.contains("audio")) {
            audio = true;
          } else {
            try {
              duration_s = Double.parseDouble(line);
            } catch (NumberFormatException e) {
              log.warn("Unknown ffprobe output: {}", line);
            }
          }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
          log.error("FFprobe process failed with exit code: {}", exitCode);
          throw new Exception("FFprobe process failed with exit code: " + exitCode);
        }

        log.info("FFprobe process completed successfully, video: {}, audio: {}", video, audio);

        return new MediaFileManifestDTO(video, audio, duration_s);
      } catch (Exception e) {
        log.error("FFprobe process failed", e);
        throw new RuntimeException("FFprobe process failed", e);
      }

    } catch (Exception e) {
      log.error("FFprobe process failed", e);
      throw new RuntimeException("FFprobe process failed", e);
    }
  }

  private Process startFFmpegProcess(String streamUrl, String videoPattern, String audioPattern) throws Exception {
    String[] command = {
        "ffmpeg",
        "-hide_banner",
        "-y",
        "-i", streamUrl, // Streaming input from Presigned URL

        // --- VIDEO CHUNKS (For Gemini) ---
        "-map", "0:v?", // Keep Video + Audio for Gemini context
        "-map", "0:a?", // Keep Video + Audio for Gemini context
        "-c", "copy", // Hardcoded 'copy': Instant splitting, 0% CPU
        "-f", "segment",
        "-segment_time", String.valueOf(AUDIO_CHUNK_DURATION_S), // Hardcoded 60s: Optimal for Gemini's context window
        "-reset_timestamps", "1",
        videoPattern,

        // --- AUDIO CHUNKS (For Groq/Whisper) ---
        "-map", "0:a?", // Extract Audio only
        "-c:a", "pcm_s16le", // Hardcoded WAV: Lossless, no CPU usage, best for Groq
        "-f", "segment",
        "-segment_time", String.valueOf(AUDIO_CHUNK_DURATION_S), // Must match video duration exactly
        "-reset_timestamps", "1",
        audioPattern
    };

    log.info("Starting FFmpeg with command: {}", String.join(" ", command));

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);

    return processBuilder.start();
  }

  private void monitorFFmpegOutput(Process process) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.debug("FFmpeg: {}", line);
        // Could parse progress here for metrics
      }
    } catch (IOException e) {
      log.error("Error reading FFmpeg output", e);
    }
  }

  private void waitForFFmpegCompletion(Process process) throws Exception {
    Instant overallDeadline = Instant.now().plus(FFMPEG_PROCESSING_TIMEOUT_M, ChronoUnit.MINUTES);
    long stallTimeoutMS = 1000 * 60 * 3;

    while (process.isAlive()) {

      long now = System.currentTimeMillis();

      if (Instant.now().isAfter(overallDeadline)) {
        process.destroyForcibly();
        throw new Exception(
            "FFmpeg process exceeded the overall timeout of " + FFMPEG_PROCESSING_TIMEOUT_M + " minutes");
      }

      if (now - lastChunkTimeMS.get() > stallTimeoutMS) {
        log.warn("FFmpeg process has been inactive for {} minutes, last chunk produced {} minutes ago",
            stallTimeoutMS / 1000 / 60, (now - lastChunkTimeMS.get()) / 1000 / 60);

        boolean exited = process.waitFor(1, TimeUnit.SECONDS);

        if (exited) {
          log.info("No more chunks being produced, FFmpeg process exited successfully");
          break;
        } else {
          process.destroyForcibly();
          log.warn("FFmpeg process stalled, no chunks produced in last {} minutes", stallTimeoutMS / 1000 / 60);
        }
      }

      Thread.sleep(1000);
    }
  }

  private void startDirectoryWatcher(Path workDir) {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
      workDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

      Path previousVideoChunk = null;
      Path previousAudioChunk = null;

      while (isRunning.get() || !uploadQueue.isEmpty()) {
        WatchKey key;
        try {
          key = watchService.poll(500, TimeUnit.MILLISECONDS);
        } catch (ClosedWatchServiceException e) {
          break;
        } catch (Exception e) {
          log.error("Error watching directory", e);
          break;
        }

        if (key == null)
          continue;

        for (WatchEvent<?> event : key.pollEvents()) {
          Path fileName = (Path) event.context();
          Path fullPath = workDir.resolve(fileName);
          String name = fileName.toString();

          lastChunkTimeMS.set(System.currentTimeMillis());

          if (name.endsWith(".mp4")) {
            if (previousVideoChunk != null) {
              log.info("Enqueuing video chunk: {}", previousVideoChunk.getFileName());
              safeEnqueue(previousVideoChunk);
            }
            previousVideoChunk = fullPath;
          } else if (name.endsWith(".wav")) {
            if (previousAudioChunk != null) {
              log.info("Enqueuing audio chunk: {}", previousAudioChunk.getFileName());
              safeEnqueue(previousAudioChunk);
            }
            previousAudioChunk = fullPath;
          }
          log.info("uploadQueue in watcher: {}", uploadQueue.toString());
        }

        boolean dirAccessible = key.reset();
        if (!dirAccessible) {
          log.warn("Work directory inaccessible. Stopping watcher.");
          break;
        }
      }

      if (previousVideoChunk != null)
        safeEnqueue(previousVideoChunk);
      if (previousAudioChunk != null)
        safeEnqueue(previousAudioChunk);

      log.info("Watcher stopped. Final chunks queued for upload");
    } catch (Exception e) {
      log.error("Error watching directory", e);
    }
  }

  private void startUploadDispatcher(String objectName, UUID fileId, MediaFileManifestDTO manifestDTO) {
    while (isRunning.get() || !uploadQueue.isEmpty()) {
      try {
        Path chunkPath = uploadQueue.poll(1, TimeUnit.SECONDS);
        if (chunkPath == null)
          continue;

        uploadSlots.acquire();

        log.info("Uploading chunk: {}", chunkPath.getFileName());

        Thread.startVirtualThread(() -> {
          chunkRegistry.put(chunkPath.toString(), UploadStatus.IN_PROGRESS);
          uploadWorker(objectName, fileId, chunkPath, manifestDTO);
        });
      } catch (Exception e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void uploadWorker(String objectName, UUID fileId, Path chunkPath, MediaFileManifestDTO manifestDTO) {
    try {
      String fileName = chunkPath.getFileName().toString();
      int index = extractChunkNumber(fileName);
      String mediaType = fileName.contains("video") ? "video" : "audio";

      log.info("Uploading chunk: {}", fileName);

      // Upload event for pipeline
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNK_UPLOAD_STARTED).chunkIndex(index)
          .message(mediaType + " chunk upload started").metadata(Map.of("mediaType", mediaType)).build());

      String gcsPath = gcsStorageService.uploadChunk(objectName, chunkPath);

      chunkRegistry.put(chunkPath.toString(), UploadStatus.UPLOADED);

      // Upload event for pipeline
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNK_UPLOAD_COMPLETE).chunkIndex(index)
          .message(mediaType + " chunk upload completed").metadata(Map.of("mediaType", mediaType)).build());

      ChunkPair chunkPair = chunkPairMap.compute(index, (k, v) -> {
        if (v == null) {
          v = new ChunkPair();
          v.setStart_s(index * AUDIO_CHUNK_DURATION_S);
          v.setEnd_s(Math.min((index + 1) * AUDIO_CHUNK_DURATION_S, manifestDTO.getDuration_s()));
        }
        if (fileName.contains("video")) {
          v.setVideoPath(gcsPath);
        } else if (fileName.contains("audio")) {
          v.setAudioPath(gcsPath);
        }
        return v;
      });

      if (chunkPair.isComplete(manifestDTO)) {
        log.info("Chunk pair {} is complete. Processing for persistence.", index);

        MediaChunk chunk = mediaChunkRepository.save(MediaChunk.builder().fileId(fileId)
            .chunkIndex(index).startTime(chunkPair.getStart_s()).endTime(chunkPair.getEnd_s())
            .status(MediaChunk.Status.UPLOADED).build());

        kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
            PipelineEventDTO.builder().fileId(fileId).chunkId(chunk.getId())
                .eventType(PipelineEventEnum.MEDIA_CHUNK_READY).chunkIndex(index)
                .message("Media chunk ready for processing").metadata(Map.of("chunkId", chunk.getId())).build());

        kafkaTemplate.send("media-chunk-uploaded",
            ChunkUploadedEventDTO.builder().chunkId(chunk.getId()).fileId(fileId).objectName(objectName)
                .chunkIndex(index).start_s(chunkPair.getStart_s()).end_s(chunkPair.getEnd_s())
                .videoPath(chunkPair.getVideoPath()).audioPath(chunkPair.getAudioPath()).build());

        totalChunks.incrementAndGet();

        chunkPairMap.remove(index);
      }

      Files.deleteIfExists(chunkPath);
    } catch (Exception e) {
      log.error("Upload failed for {}", chunkPath, e);
      chunkRegistry.put(chunkPath.toString(), UploadStatus.FAILED);
      // Retry logic
    } finally {
      uploadSlots.release();
    }
  }

  private Path createWorkingDir(String objectName) throws IOException {
    log.info("Creating work directory.");
    Path workDir = Paths.get(TEMP_DIRECTORY, objectName);
    Files.createDirectories(workDir);
    log.info("Created work directory successfully.");
    return workDir;
  }

  private void cleanUpWorkingDir(Path workDir, String objectName) {
    log.info("Starting cleanup for working directory: {}", workDir);

    try {
      // 1. Sweep for any remaining chunks that weren't uploaded
      try (var files = Files.list(workDir)) {
        files.filter(path -> {
          String name = path.getFileName().toString();
          return name.endsWith(".mp4") || name.endsWith(".wav");
        })
            .filter(path -> {
              String key = path.toString();
              UploadStatus status = chunkRegistry.get(key);
              return status == null || status == UploadStatus.PENDING || status == UploadStatus.FAILED;
            })
            .forEach(path -> {
              log.info("Found remaining chunk during cleanup: {}", path.getFileName());
              safeEnqueue(path);
            });
      }

      // 2. Wait for upload queue to drain
      while (!uploadQueue.isEmpty()) {
        log.info("uploadqueue in cleanup: {}", uploadQueue.toString());
        log.info("Waiting for {} remaining uploads...", uploadQueue.size());
        Thread.sleep(1000);
      }

      // 3. Wait for all in-progress uploads to complete (all semaphore slots
      // released)
      uploadSlots.acquire(MAX_CONCURRENT_UPLOADS);
      uploadSlots.release(MAX_CONCURRENT_UPLOADS);

      // 4. Verify all uploads completed successfully
      long failedCount = chunkRegistry.values().stream()
          .filter(status -> status == UploadStatus.FAILED)
          .count();

      if (failedCount > 0) {
        log.warn("{} chunks failed to upload. Skipping directory deletion.", failedCount);
        return;
      }

      // 5. Delete the working directory recursively
      try (var paths = Files.walk(workDir)) {
        paths.sorted((a, b) -> b.compareTo(a)) // Reverse order: files before directories
            .forEach(path -> {
              try {
                Files.deleteIfExists(path);
              } catch (IOException e) {
                log.warn("Failed to delete: {}", path, e);
              }
            });
      }

      log.info("Successfully cleaned up working directory: {}", workDir);

    } catch (Exception e) {
      log.error("Error during cleanup of working directory: {}", workDir, e);
    }
  }

  private void safeEnqueue(Path path) {
    UploadStatus currentStatus = chunkRegistry.get(path.toString());
    if (currentStatus == UploadStatus.UPLOADED || currentStatus == UploadStatus.IN_PROGRESS
        || currentStatus == UploadStatus.PENDING) {
      log.info("Chunk {} is already being processed or uploaded or is pending. Skipping.", path.getFileName());
      return;
    }
    chunkRegistry.put(path.toString(), UploadStatus.PENDING);
    uploadQueue.offer(path);
  }

  private int extractChunkNumber(String fileName) {
    Pattern pattern = Pattern.compile(".*?(\\d+)\\.(mp4|wav)");
    Matcher matcher = pattern.matcher(fileName);

    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    return -1;
  }

  // private void processAudioChunk(byte[] audioChunk, int index) {
  // CompletableFuture.runAsync(() -> {
  // try {
  // byte[] wavData = WavUtils.addHeader(audioChunk);

  // Map<String, Object> response = transcriptionService.transcribe(wavData);

  // String transcript = (String) response.get("text");

  // String language = (String) response.get("language");

  // log.info("Transcribed audio chunk #{} in language {}: {}", index, language,
  // transcript);
  // } catch (Exception e) {
  // log.error("Transcription failed: ", e);
  // }
  // });
  // }

  // private byte[] convertSampleToBytes(Frame frame) {
  // ShortBuffer sb = (ShortBuffer) frame.samples[0];
  // int samples = sb.limit();
  // byte[] bytes = new byte[samples * 2];

  // for (int i = 0; i < samples; i++) {
  // short sample = sb.get(i);
  // bytes[i * 2] = (byte) (sample & 0xff);
  // bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
  // }

  // return bytes;
  // }

}
