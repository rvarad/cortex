package com.cortex.cortex_media_processing_service.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
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
import com.cortex.cortex_media_processing_service.service.processing.ChunkPair;
import com.cortex.cortex_media_processing_service.service.processing.MediaProcessingContext;
import com.cortex.cortex_media_processing_service.service.processing.UploadStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.cortex.cortex_common.utils.MdcUtils.withMDC;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

  private final GcsStorageService gcsStorageService;

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final KafkaTemplate<String, Object> kafkaTemplate;

  private final ObjectMapper objectMapper;

  @Value("${app.kafka.topic.pipeline-events}")
  private String pipelineEventsTopic;

  @Value("${cortex.media.max-duration-seconds}")
  private double maxDurationInSeconds;

  private static final int MAX_CONCURRENT_UPLOADS = 5;
  private static final int MAX_CONCURRENT_NORMALISATIONS = 2;

  private static final int AUDIO_CHUNK_DURATION_S = 60;
  private static final int FFMPEG_PROCESSING_TIMEOUT_M = 30;

  private static final double SILENCE_FLOOR_DB = -50.0;

  private static final String TEMP_DIRECTORY = "/tmp/media-processing-service-chunks";

  private static enum NormalisationEnum {
    PASSTHROUGH,
    REMUX,
    TRANSCODE_VIDEO,
    TRANSCODE_AUDIO
  }

  private static final Pattern MAX_VOLUME = Pattern.compile("max_volume:\\s*(-?[\\d.]+)");

  private final Semaphore uploadSlots = new Semaphore(MAX_CONCURRENT_UPLOADS);
  private final Semaphore normalisationSlots = new Semaphore(MAX_CONCURRENT_NORMALISATIONS);

  public void processMedia(String objectName, UUID fileId, String userId) {
    log.info("Beginning chunking for file: {}", objectName);

    MediaProcessingContext ctx = null;

    try {
      String streamUrl = gcsStorageService.getPresignedUrl(objectName);

      MediaFileManifestDTO manifestDTO = probeMediaFile(streamUrl);
      if (manifestDTO.isCorrupted()) {
        log.error("Media file is corrupted: {}", objectName);
        throw new RuntimeException("Media file is corrupted: " + objectName);
      }

      FileMetadata fileMetadata = fileMetadataRepository.findById(fileId).orElseThrow(() -> {
        log.error("[GCSService] File metadata not found for objectName: {}", objectName);
        return new RuntimeException("File metadata not found for objectName: " + objectName);
      });

      if (manifestDTO.getDuration_s() > maxDurationInSeconds) {
        gcsStorageService.deleteObject(objectName);
        fileMetadata.setFileStatus(FileStatusEnum.REJECTED);
        fileMetadataRepository.save(fileMetadata);
        log.info("[MediaProcessingService] File too long: {}", objectName);

        PipelineEventDTO pipelineEventDTO = PipelineEventDTO.builder().fileId(fileMetadata.getId())
            .eventType(PipelineEventEnum.UPLOAD_REJECTED)
            .message("File is too long to be processed. Please upload a file shorter than "
                + (maxDurationInSeconds / 60) + " minutes. This file with duration of "
                + Math.round(manifestDTO.getDuration_s() / 60) + " minutes will be deleted.")
            .metadata(Map.of(
                "contentType", fileMetadata.getContentType(),
                "durationInSeconds", manifestDTO.getDuration_s(),
                "objectName", fileMetadata.getObjectName(),
                "bucketName", fileMetadata.getBucketName(),
                "fileStatus", fileMetadata.getFileStatus().toString()))
            .build();

        kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), pipelineEventDTO);

        return;
      }

      // moovAtFront
      BooleanSupplier moovAtFront = () -> isMoovBeforeMdat(gcsStorageService.readHead(objectName, 4 << 20)); // 4mB
                                                                                                             // slice

      // decideNormalisationStrategy
      NormalisationEnum plan = decideNormalisationStrategy(fileMetadata.getContentType(), manifestDTO, moovAtFront);

      Path workDir = createWorkingDir(objectName);
      log.info("Created working directory: {}", workDir.toString());
      log.info("Created working directory: {}", workDir.getFileName().toString());

      ctx = new MediaProcessingContext(
          fileId,
          objectName,
          userId,
          workDir,
          manifestDTO);

      MediaProcessingContext processContext = ctx;

      String videoPattern = workDir.resolve("chunks").resolve("video_chunk_%03d.mp4").toString();
      String audioPattern = workDir.resolve("chunks").resolve("audio_chunk_%03d.wav").toString();

      Thread normalisationThread = null;

      if (plan.equals(NormalisationEnum.PASSTHROUGH)) {
        fileMetadataRepository.updatePlaybackReady(fileId, objectName);
      } else {
        normalisationThread = Thread.startVirtualThread(withMDC(() -> {
          try {
            normalisationSlots.acquire();

            try {
              kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
                  PipelineEventDTO.builder().fileId(fileId).eventType(PipelineEventEnum.NORMALISATION_STARTED)
                      .message("Generating playback version").build());

              Path local = null;
              if (plan.equals(NormalisationEnum.REMUX)) {
                local = remuxFile(streamUrl, workDir);
              } else if (plan.equals(NormalisationEnum.TRANSCODE_AUDIO)) {
                local = transcodeAudio(streamUrl, workDir);
              } else {
                local = transcodeVideo(streamUrl, workDir);
              }

              String extension = plan.equals(NormalisationEnum.TRANSCODE_AUDIO) ? ".m4a" : ".mp4";
              String contentType = plan.equals(NormalisationEnum.TRANSCODE_AUDIO) ? "audio/mp4" : "video/mp4";
              String destinationObjectName = "playback/" + fileId + extension;
              gcsStorageService.uploadFile(destinationObjectName, local, contentType);

              fileMetadataRepository.updatePlaybackReady(fileId, destinationObjectName);
              Files.deleteIfExists(local);

              kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
                  PipelineEventDTO.builder().fileId(fileId).eventType(PipelineEventEnum.NORMALISATION_COMPLETE)
                      .message("Playback version is ready").build());
            } finally {
              normalisationSlots.release();
            }
          } catch (Exception e) {
            log.error("Normalisation failed for {}", fileId, e);
            // Persist the terminal playback state BEFORE announcing, so any
            // consumer reacting to NORMALIZATION_FAILED reads UNAVAILABLE.
            fileMetadataRepository.updatePlaybackUnavailable(fileId);
            kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
                PipelineEventDTO.builder().fileId(fileId).eventType(PipelineEventEnum.NORMALISATION_FAILED)
                    .message("Playback version could not be generated").build());
          }
        }));
      }

      Thread.startVirtualThread(withMDC(() -> startDirectoryWatcher(processContext)));
      Thread.startVirtualThread(withMDC(() -> startUploadDispatcher(processContext)));

      processContext.getLastChunkTimeMS().set(System.currentTimeMillis());

      processContext.getTotalChunks().set(0);

      PipelineEventDTO pipelineEventDTO = PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNKING_STARTED).message("Chunking started").build();
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), pipelineEventDTO);

      Process ffmpegProcess = startFFmpegChunkingProcess(streamUrl, videoPattern, audioPattern,
          processContext.getManifestDTO());

      Thread.startVirtualThread(withMDC(() -> monitorFFmpegOutput(ffmpegProcess)));

      waitForFFmpegCompletion(ffmpegProcess, processContext.getLastChunkTimeMS());

      int exitCode = ffmpegProcess.exitValue();
      if (exitCode != 0) {
        log.error("FFmpeg process failed with exit code: {}", exitCode);
        throw new Exception("FFmpeg process failed with exit code: " + exitCode);
      }

      log.info("FFmpeg process completed successfully");

      cleanUpWorkingDir(processContext);

      processContext.getIsRunning().set(false);

      int totalChunksCount = processContext.getTotalChunks().get();

      // Persist the chunking result NOW via a targeted update — deliberately NOT a
      // full save, so it leaves playbackObjectName alone (the normalization thread
      // owns that column). This makes totalChunks durable before CHUNKING_COMPLETE
      // fires and before embeddings finish, so the completion check can read it.
      fileMetadataRepository.updateChunkingResult(fileId, FileStatusEnum.CHUNKED, totalChunksCount,
          manifestDTO.getDuration_s(), manifestDTO.isHasVideo(), manifestDTO.isHasAudio(),
          manifestDTO.getVideoCodec());
      log.info("[MediaProcessingService] Set totalChunks = {} for fileId: {}", totalChunksCount, fileId);

      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
          PipelineEventDTO.builder().fileId(fileId).eventType(PipelineEventEnum.CHUNKING_COMPLETE)
              .message("Media chunking finished successfully").metadata(Map.of("totalChunks", totalChunksCount))
              .build());

      // Hold the Kafka message open until normalization also finishes
      // (retry-correctness).
      if (normalisationThread != null)
        normalisationThread.join();

      // Recursive (not deleteIfExists, which throws on a non-empty dir): on a
      // normalization failure a partial normalized.* file can be left behind, and
      // we must not let cleanup turn a best-effort failure into a pipeline failure.
      deleteRecursively(workDir);

      log.info("Media processing completed and cleaned up.");

    } catch (Exception e) {
      log.error("FFmpeg execution failed", e);
      // processContext.getIsRunning().set(false); // Ensure we signal shutdown even
      // on error
      throw new RuntimeException("Failed to process media file: " + objectName + " exception: " + e);
    } finally {
      if (ctx != null) {
        ctx.getIsRunning().set(false);
      }
    }
  }

  private NormalisationEnum decideNormalisationStrategy(String contentType, MediaFileManifestDTO manifestDTO,
      BooleanSupplier moovAtFront) {
    if ("audio/mpeg".equalsIgnoreCase(contentType) || "video/webm".equalsIgnoreCase(contentType)) {
      return NormalisationEnum.PASSTHROUGH;
    } else if ("audio/wav".equalsIgnoreCase(contentType)) {
      return NormalisationEnum.TRANSCODE_AUDIO;
    } else if ("video/mp4".equalsIgnoreCase(contentType)) {
      if (!manifestDTO.isHasVideo())
        return NormalisationEnum.PASSTHROUGH;
      if (!"h264".equalsIgnoreCase(manifestDTO.getVideoCodec())) {
        return NormalisationEnum.TRANSCODE_VIDEO;
      } else {
        return moovAtFront.getAsBoolean() ? NormalisationEnum.PASSTHROUGH : NormalisationEnum.REMUX;
      }
    } else {
      throw new IllegalStateException("This is bad");
    }
  }

  private boolean isAudioSilent(String streamUrl) {
    log.info("Checking if audio is silent: {}", streamUrl);
    Double maxVolume = null;

    try {
      ProcessBuilder processBuilder = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostats",
          "-i", streamUrl, "-vn", "-af", "volumedetect", "-f", "null", "-");

      Process process = processBuilder.start();

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Matcher matcher = MAX_VOLUME.matcher(line);
          if (matcher.find()) {
            maxVolume = Double.parseDouble(matcher.group(1));
          }
        }
      }
      process.waitFor();

      if (maxVolume == null)
        return false;

      return maxVolume < SILENCE_FLOOR_DB;
    } catch (Exception e) {
      log.error("Error checking if audio is silent", e);
      return false;
    }
  }

  private static boolean isMoovBeforeMdat(byte[] head) {
    long position = 0;
    while (position + 8 <= head.length) {
      long size = readSize(head, (int) position);
      String name = readName(head, (int) position);

      if ("moov".equalsIgnoreCase(name))
        return true;
      if ("mdat".equalsIgnoreCase(name))
        return false;

      if (size < 8)
        return false;
      position += size;
    }

    return false;
  }

  private static long readSize(byte[] data, int position) {
    return ((long) (data[position] & 0xFF) << 24)
        | ((long) (data[position + 1] & 0xFF) << 16)
        | ((long) (data[position + 2] & 0xFF) << 8)
        | ((long) (data[position + 3] & 0xFF));
  }

  private static String readName(byte[] data, int position) {
    return new String(data, position + 4, 4, StandardCharsets.US_ASCII);
  }

  private MediaFileManifestDTO probeMediaFile(String streamUrl) {
    log.info("Probing media file: {}", streamUrl);

    try {
      ProcessBuilder processBuilder = new ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json",
          "-show_format", "-show_streams",
          streamUrl);

      Process process = processBuilder.start();

      boolean video = false;
      boolean audio = false;

      boolean audioStreamExists = false;
      JsonNode realVideoStream = null;

      double duration_s = 0.0;

      try {
        JsonNode root = objectMapper.readTree(process.getInputStream());

        duration_s = root.path("format").path("duration").asDouble(0.0);

        for (JsonNode stream : root.path("streams")) {
          String codecType = stream.path("codec_type").asText();

          if (codecType.equals("audio")) {
            audioStreamExists = true;
          } else if (codecType.equals("video")) {
            int attachedPic = stream.path("disposition").path("attached_pic").asInt(0);
            if (attachedPic != 1)
              realVideoStream = stream;
          }
        }

        audio = audioStreamExists && !isAudioSilent(streamUrl);
        video = realVideoStream != null;

        String videoCodec = video ? realVideoStream.path("codec_name").asText() : null;

        int exitCode = process.waitFor();
        if (exitCode != 0) {
          log.error("FFprobe process failed with exit code: {}", exitCode);
          throw new Exception("FFprobe process failed with exit code: " + exitCode);
        }

        log.info("FFprobe process completed successfully, video: {}, audio: {}", video, audio);

        return MediaFileManifestDTO.builder().duration_s(duration_s).hasVideo(video).hasAudio(audio)
            .videoCodec(videoCodec).build();
      } catch (Exception e) {
        log.error("FFprobe process failed", e);
        throw new RuntimeException("FFprobe process failed", e);
      }

    } catch (Exception e) {
      log.error("FFprobe process failed", e);
      throw new RuntimeException("FFprobe process failed", e);
    }
  }

  private Process startFFmpegChunkingProcess(String streamUrl, String videoPattern, String audioPattern,
      MediaFileManifestDTO manifest) throws Exception {

    log.info("Starting FFmpeg process for media file: {}", streamUrl);

    List<String> commandList = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-y", "-i", streamUrl));

    if (manifest.isHasVideo()) {
      commandList.addAll(List.of(
          "-map", "0:v:0",
          "-map", "0:a:0?",
          "-c", "copy",
          "-f", "segment",
          "-segment_time", String.valueOf(AUDIO_CHUNK_DURATION_S),
          "-reset_timestamps", "1",
          videoPattern));
    }

    if (manifest.isHasAudio()) {
      commandList.addAll(List.of(
          "-map", "0:a:0",
          "-c:a", "pcm_s16le",
          "-f", "segment",
          "-segment_time", String.valueOf(AUDIO_CHUNK_DURATION_S),
          "-reset_timestamps", "1",
          audioPattern));
    }

    log.info("Starting FFmpeg with command: {}", String.join(" ", commandList));

    ProcessBuilder processBuilder = new ProcessBuilder(commandList);
    processBuilder.redirectErrorStream(true);

    return processBuilder.start();
  }

  /**
   * REMUX: h264 already, just move the moov atom to the front (faststart) so the
   * browser can seek without downloading the whole file. `-c copy` = no
   * re-encode.
   * Returns the local output file (caller uploads it + sets
   * playback_object_name).
   */
  private Path remuxFile(String streamUrl, Path workDir) {
    try {
      Path output = workDir.resolve("normalized.mp4");
      List<String> command = List.of("ffmpeg", "-hide_banner", "-y", "-i", streamUrl,
          "-map", "0:v:0", "-map", "0:a:0?",
          "-c", "copy",
          "-movflags", "+faststart",
          output.toString());
      runNormalization(command, "REMUX");
      return output;
    } catch (Exception e) {
      log.error("[MediaProcessingService] Failed to remux file :{}, Due to: {}", streamUrl, e);
      throw new RuntimeException("[MediaProcessingService] Failed to remux file : " + streamUrl, e);
    }
  }

  /**
   * TRANSCODE_VIDEO: re-encode a non-h264 codec (HEVC/AV1) to browser-playable
   * h264. `-pix_fmt yuv420p` is mandatory — iPhone HEVC is often 10-bit HDR, and
   * without forcing 8-bit 4:2:0 the h264 output still won't play in Chrome.
   */
  private Path transcodeVideo(String streamUrl, Path workDir) {
    try {
      Path output = workDir.resolve("normalized.mp4");
      List<String> command = List.of("ffmpeg", "-hide_banner", "-y", "-i", streamUrl,
          "-map", "0:v:0", "-map", "0:a:0?",
          "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
          "-c:a", "aac", "-b:a", "128k",
          "-movflags", "+faststart",
          "-threads", "2",
          output.toString());
      runNormalization(command, "TRANSCODE_VIDEO");
      return output;
    } catch (Exception e) {
      log.error("[MediaProcessingService] Failed to transcode file :{}, Due to: {}", streamUrl, e);
      throw new RuntimeException("[MediaProcessingService] Failed to transcode file : " + streamUrl, e);
    }
  }

  /**
   * TRANSCODE_AUDIO: WAV (uncompressed) to AAC/m4a — a ~10:1 storage/bandwidth
   * win.
   */
  private Path transcodeAudio(String streamUrl, Path workDir) {
    try {
      Path output = workDir.resolve("normalized.m4a");
      List<String> command = List.of("ffmpeg", "-hide_banner", "-y", "-i", streamUrl,
          "-map", "0:a:0", "-vn",
          "-c:a", "aac", "-b:a", "192k",
          "-movflags", "+faststart",
          output.toString());
      runNormalization(command, "TRANSCODE_AUDIO");
      return output;
    } catch (Exception e) {
      log.error("[MediaProcessingService] Failed to transcode file :{}, Due to: {}", streamUrl, e);
      throw new RuntimeException("[MediaProcessingService] Failed to transcode file : " + streamUrl, e);
    }
  }

  /**
   * Runs a normalization ffmpeg command to completion (single output file, no
   * streaming chunks). Drains output on a virtual thread so the pipe buffer can't
   * block ffmpeg, enforces the overall timeout, and throws on non-zero exit.
   */
  private void runNormalization(List<String> command, String label) throws Exception {
    log.info("Starting normalization ({}) with command: {}", label, String.join(" ", command));

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();

    Thread.startVirtualThread(withMDC(() -> monitorFFmpegOutput(process)));

    boolean finished = process.waitFor(FFMPEG_PROCESSING_TIMEOUT_M, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      throw new Exception(
          "Normalization (" + label + ") exceeded the timeout of " + FFMPEG_PROCESSING_TIMEOUT_M + " minutes");
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
      throw new Exception("Normalization (" + label + ") failed with exit code: " + exitCode);
    }

    log.info("Normalization ({}) completed successfully: {}", label, command.get(command.size() - 1));
  }

  private void monitorFFmpegOutput(Process process) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.debug("FFmpeg debug: {}", line);
        log.info("FFmpeg info: {}", line);
        // Could parse progress here for metrics
      }
    } catch (IOException e) {
      log.error("Error reading FFmpeg output", e);
    }
  }

  private void waitForFFmpegCompletion(Process process, AtomicLong lastChunkTimeMS) throws Exception {
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

  private void startDirectoryWatcher(MediaProcessingContext processContext) {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {

      Path chunksDir = processContext.getWorkDir().resolve("chunks");
      chunksDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

      Path previousVideoChunk = null;
      Path previousAudioChunk = null;

      while (processContext.getIsRunning().get() || !processContext.getUploadQueue().isEmpty()) {
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
          Path fullPath = chunksDir.resolve(fileName);
          String name = fileName.toString();

          processContext.getLastChunkTimeMS().set(System.currentTimeMillis());

          if (name.endsWith(".mp4")) {
            if (previousVideoChunk != null) {
              log.info("Enqueuing video chunk: {}", previousVideoChunk.getFileName());
              safeEnqueue(processContext, previousVideoChunk);
            }
            previousVideoChunk = fullPath;
          } else if (name.endsWith(".wav")) {
            if (previousAudioChunk != null) {
              log.info("Enqueuing audio chunk: {}", previousAudioChunk.getFileName());
              safeEnqueue(processContext, previousAudioChunk);
            }
            previousAudioChunk = fullPath;
          }
          log.info("uploadQueue in watcher: {}", processContext.getUploadQueue().toString());
        }

        boolean dirAccessible = key.reset();
        if (!dirAccessible) {
          log.warn("Work directory inaccessible. Stopping watcher.");
          break;
        }
      }

      if (previousVideoChunk != null)
        safeEnqueue(processContext, previousVideoChunk);
      if (previousAudioChunk != null)
        safeEnqueue(processContext, previousAudioChunk);

      log.info("Watcher stopped. Final chunks queued for upload");
    } catch (Exception e) {
      log.error("Error watching directory", e);
    }
  }

  private void startUploadDispatcher(MediaProcessingContext processContext) {
    while (processContext.getIsRunning().get() || !processContext.getUploadQueue().isEmpty()) {
      try {
        Path chunkPath = processContext.getUploadQueue().poll(1, TimeUnit.SECONDS);
        if (chunkPath == null)
          continue;

        uploadSlots.acquire();

        log.info("Uploading chunk: {}", chunkPath.getFileName());

        Thread.startVirtualThread(withMDC(() -> {
          processContext.getChunkUploadStatusMap().put(chunkPath.toString(),
              com.cortex.cortex_media_processing_service.service.processing.UploadStatus.IN_PROGRESS);
          uploadWorker(processContext, chunkPath);
        }));
      } catch (Exception e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void uploadWorker(MediaProcessingContext processContext, Path chunkPath) {
    try {
      UUID fileId = processContext.getFileId();
      String objectName = processContext.getObjectName();
      String fileName = chunkPath.getFileName().toString();
      int index = extractChunkNumber(fileName);
      String mediaType = fileName.contains("video") ? "video" : "audio";

      log.info("Uploading chunk: {}", fileName);

      // Upload event for pipeline
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNK_UPLOAD_STARTED).chunkIndex(index)
          .message(mediaType + " chunk upload started").metadata(Map.of("mediaType", mediaType)).build());

      String gcsPath = gcsStorageService.uploadChunk(objectName, chunkPath);

      processContext.getChunkUploadStatusMap().put(chunkPath.toString(), UploadStatus.UPLOADED);

      // Upload event for pipeline
      kafkaTemplate.send(pipelineEventsTopic, fileId.toString(), PipelineEventDTO.builder().fileId(fileId)
          .eventType(PipelineEventEnum.CHUNK_UPLOAD_COMPLETE).chunkIndex(index)
          .message(mediaType + " chunk upload completed").metadata(Map.of("mediaType", mediaType)).build());

      ChunkPair chunkPair = processContext.getChunkPairMap().compute(index, (k, v) -> {
        if (v == null) {
          v = new ChunkPair();
          v.setStart_s(index * AUDIO_CHUNK_DURATION_S);
          v.setEnd_s(Math.min((index + 1) * AUDIO_CHUNK_DURATION_S, processContext.getManifestDTO().getDuration_s()));
        }
        if (fileName.contains("video")) {
          v.setVideoPath(gcsPath);
        } else if (fileName.contains("audio")) {
          v.setAudioPath(gcsPath);
        }
        return v;
      });

      if (chunkPair.isComplete(processContext.getManifestDTO())) {
        log.info("Chunk pair {} is complete. Processing for persistence.", index);

        MediaChunk chunk = mediaChunkRepository.save(MediaChunk.builder().fileId(fileId)
            .chunkIndex(index).startTime(chunkPair.getStart_s()).endTime(chunkPair.getEnd_s())
            .status(MediaChunk.Status.UPLOADED).userId(processContext.getUserId()).build());

        kafkaTemplate.send(pipelineEventsTopic, fileId.toString(),
            PipelineEventDTO.builder().fileId(fileId).chunkId(chunk.getId())
                .eventType(PipelineEventEnum.MEDIA_CHUNK_READY).chunkIndex(index)
                .message("Media chunk ready for processing").metadata(Map.of("chunkId", chunk.getId())).build());

        kafkaTemplate.send("media-chunk-uploaded",
            ChunkUploadedEventDTO.builder().chunkId(chunk.getId()).fileId(fileId).objectName(objectName)
                .chunkIndex(index).start_s(chunkPair.getStart_s()).end_s(chunkPair.getEnd_s())
                .videoPath(chunkPair.getVideoPath()).audioPath(chunkPair.getAudioPath())
                .userId(processContext.getUserId()).build());

        processContext.getTotalChunks().incrementAndGet();

        processContext.getChunkPairMap().remove(index);
      }

      Files.deleteIfExists(chunkPath);
    } catch (Exception e) {
      log.error("Upload failed for {}", chunkPath, e);
      processContext.getChunkUploadStatusMap().put(chunkPath.toString(), UploadStatus.FAILED);
      // Retry logic
    } finally {
      uploadSlots.release();
    }
  }

  private Path createWorkingDir(String objectName) throws IOException {
    log.info("Creating work directory.");
    Path workDir = Paths.get(TEMP_DIRECTORY, objectName);
    // Chunks live in a "chunks/" subdir so the watcher can be scoped to it — the
    // normalized playback file sits at the workDir (parent) level, invisible to
    // the watcher, so it's never mistaken for a chunk. createDirectories makes
    // both.
    Files.createDirectories(workDir.resolve("chunks"));
    log.info("Created work directory successfully.");
    return workDir;
  }

  private void cleanUpWorkingDir(MediaProcessingContext processContext) {
    Path chunksDir = processContext.getWorkDir().resolve("chunks");
    log.info("Starting cleanup for chunks directory: {}", chunksDir);

    try {
      // 1. Sweep for any remaining chunks that weren't uploaded
      try (var files = Files.list(chunksDir)) {
        files.filter(path -> {
          String name = path.getFileName().toString();
          return name.endsWith(".mp4") || name.endsWith(".wav");
        })
            .filter(path -> {
              String key = path.toString();
              UploadStatus status = processContext.getChunkUploadStatusMap().get(key);
              return status == null || status == UploadStatus.PENDING || status == UploadStatus.FAILED;
            })
            .forEach(path -> {
              log.info("Found remaining chunk during cleanup: {}", path.getFileName());
              safeEnqueue(processContext, path);
            });
      }

      // 2. Wait for upload queue to drain
      while (!processContext.getUploadQueue().isEmpty()) {
        log.info("uploadqueue in cleanup: {}", processContext.getUploadQueue().toString());
        log.info("Waiting for {} remaining uploads...", processContext.getUploadQueue().size());
        Thread.sleep(1000);
      }

      // 3. Wait for all in-progress uploads to complete (all semaphore slots
      // released)
      uploadSlots.acquire(MAX_CONCURRENT_UPLOADS);
      uploadSlots.release(MAX_CONCURRENT_UPLOADS);

      // 4. Verify all uploads completed successfully
      long failedCount = processContext.getChunkUploadStatusMap().values().stream()
          .filter(status -> status == UploadStatus.FAILED)
          .count();

      if (failedCount > 0) {
        log.warn("{} chunks failed to upload. Skipping directory deletion.", failedCount);
        return;
      }

      // 5. Delete the chunks directory recursively (NOT the parent workDir — the
      // normalized playback file lives there and may still be in use by the
      // normalization thread; the parent is removed by the wait-for-both wiring).
      try (var paths = Files.walk(chunksDir)) {
        paths.sorted((a, b) -> b.compareTo(a)) // Reverse order: files before directories
            .forEach(path -> {
              try {
                Files.deleteIfExists(path);
              } catch (IOException e) {
                log.warn("Failed to delete: {}", path, e);
              }
            });
      }

      log.info("Successfully cleaned up chunks directory: {}", chunksDir);

    } catch (Exception e) {
      log.error("Error during cleanup of working directory: {}", processContext.getWorkDir(), e);
    }
  }

  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir))
      return;
    try (var paths = Files.walk(dir)) {
      paths.sorted((a, b) -> b.compareTo(a)) // files before their parent directories
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              log.warn("Failed to delete: {}", path, e);
            }
          });
    } catch (IOException e) {
      log.warn("Failed to walk {} for deletion", dir, e);
    }
  }

  private void safeEnqueue(MediaProcessingContext processContext, Path path) {
    UploadStatus currentStatus = processContext.getChunkUploadStatusMap().get(path.toString());
    if (currentStatus == UploadStatus.UPLOADED || currentStatus == UploadStatus.IN_PROGRESS
        || currentStatus == UploadStatus.PENDING) {
      log.info("Chunk {} is already being processed or uploaded or is pending. Skipping.", path.getFileName());
      return;
    }
    processContext.getChunkUploadStatusMap().put(path.toString(), UploadStatus.PENDING);
    processContext.getUploadQueue().offer(path);
  }

  private int extractChunkNumber(String fileName) {
    Pattern pattern = Pattern.compile(".*?(\\d+)\\.(mp4|wav)");
    Matcher matcher = pattern.matcher(fileName);

    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    return -1;
  }
}
