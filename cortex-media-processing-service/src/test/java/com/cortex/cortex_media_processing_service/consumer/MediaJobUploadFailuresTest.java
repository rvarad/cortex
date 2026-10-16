package com.cortex.cortex_media_processing_service.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_media_processing_service.config.MediaProcessingConfig;
import com.cortex.cortex_media_processing_service.service.GcsStorageService;
import com.cortex.cortex_media_processing_service.service.MediaProcessingService;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * The upload workers are work the job waits for, through cleanup: a chunk that can't be uploaded
 * must fail the job, not leave a hole in the file (ADR-0014). Cleanup tries a failed chunk once
 * more, so a chunk that fails once still completes.
 *
 * <p>Runs the real job, ffprobe and ffmpeg included, on a 130 s video made by ffmpeg: three 60 s
 * chunk pairs. GCS, Kafka and the repositories are fakes. Skipped when ffmpeg or ffprobe is
 * missing, as in CI.
 */
class MediaJobUploadFailuresTest {

  @TempDir
  static Path videoDir;

  private static Path video;

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /** Freezes each event as it arrives — logback reads MDC lazily, from whoever asks first. */
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  private final GcsStorageService gcsStorageService = mock(GcsStorageService.class);

  private final MediaChunkRepository mediaChunkRepository = mock(MediaChunkRepository.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

  private final MediaProcessingService mediaProcessingService = new MediaProcessingService(
      gcsStorageService, mediaChunkRepository, fileMetadataRepository, kafkaTemplate, new ObjectMapper());

  private final ThreadPoolTaskExecutor executor = new MediaProcessingConfig().mediaProcessingExecutor();

  private final MediaEventConsumer consumer = new MediaEventConsumer(executor, mediaProcessingService);

  private final UUID fileId = UUID.randomUUID();

  private final String objectName = "uploads/media/" + fileId + ".mp4";

  @BeforeAll
  static void makeVideo() throws Exception {
    assumeTrue(runs("ffmpeg", "-version") && runs("ffprobe", "-version"), "needs ffmpeg and ffprobe");
    video = videoDir.resolve("talk.mp4");
    // -g 5: a keyframe every second, so `-c copy` can cut the video at each 60 s mark, as it does
    // the audio. faststart puts the moov atom first, so the job plays it as it is (no normalisation).
    Process ffmpeg = new ProcessBuilder("ffmpeg", "-hide_banner", "-y",
        "-f", "lavfi", "-i", "testsrc=duration=130:size=160x120:rate=5",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=130",
        "-c:v", "libx264", "-preset", "ultrafast", "-g", "5", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", "-movflags", "+faststart", video.toString())
        .redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start();
    assertThat(ffmpeg.waitFor(60, TimeUnit.SECONDS)).as("the test video was made").isTrue();
    assertThat(ffmpeg.exitValue()).isZero();
  }

  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(mediaProcessingService, "maxDurationInSeconds", 3600.0);
    ReflectionTestUtils.setField(mediaProcessingService, "pipelineEventsTopic", "pipeline-events");

    byte[] videoBytes = Files.readAllBytes(video);
    when(gcsStorageService.getPresignedUrl(anyString())).thenReturn(video.toString());
    when(gcsStorageService.readHead(anyString(), anyInt()))
        .thenAnswer(invocation -> Arrays.copyOf(videoBytes, Math.min(videoBytes.length, invocation.getArgument(1))));
    when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName(objectName)
        .fileSize((long) videoBytes.length)
        .fileStatus(FileStatusEnum.UPLOADED)
        .contentType("video/mp4")
        .userId("user-1")
        .build()));
    when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> CompletableFuture.completedFuture(null));
    when(kafkaTemplate.send(anyString(), any())).thenAnswer(invocation -> CompletableFuture.completedFuture(null));
    when(mediaChunkRepository.save(any(MediaChunk.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    when(gcsStorageService.uploadChunk(anyString(), any(Path.class)))
        .thenAnswer(invocation -> "chunks/" + invocation.<Path>getArgument(1).getFileName());

    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() throws IOException {
    cortexLogger.detachAppender(captured);
    executor.shutdown();
    MDC.clear();
    // A failed job leaves its work directory behind until brick 14.
    Path workDir = Paths.get("/tmp/media-processing-service-chunks", objectName);
    if (Files.exists(workDir)) {
      try (Stream<Path> paths = Files.walk(workDir)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @Test
  void aChunkThatFailsTwice_failsTheJob_insteadOfCompletingTheFileWithAHole() throws Exception {
    // ---- Arrange: chunk 1's media-chunk-uploaded send fails after its row was saved. Cleanup's
    // retry then saves the row again, which the real table refuses (uq_media_chunk_file_chunk) ----
    Set<Integer> savedChunks = ConcurrentHashMap.newKeySet();
    when(mediaChunkRepository.save(any(MediaChunk.class))).thenAnswer(invocation -> {
      MediaChunk chunk = invocation.getArgument(0);
      if (!savedChunks.add(chunk.getChunkIndex())) {
        throw new DataIntegrityViolationException("duplicate key value violates \"uq_media_chunk_file_chunk\"");
      }
      return withId(chunk);
    });
    when(kafkaTemplate.send(anyString(), any())).thenAnswer(invocation -> {
      ChunkUploadedEventDTO event = invocation.getArgument(1);
      return event.getChunkIndex() == 1
          ? CompletableFuture.failedFuture(new KafkaException("broker unreachable"))
          : CompletableFuture.completedFuture(null);
    });

    // ---- Act ----
    runTheJob();

    // ---- Assert: one ERROR line, from the job's boundary, with the first cause ----
    List<ILoggingEvent> errors = linesAt(Level.ERROR);
    assertThat(errors).as("ERROR lines from our code").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the job's boundary").isEqualTo(BackgroundTask.class.getName());
    assertThat(error.getMDCPropertyMap()).containsEntry("fileId", fileId.toString());
    assertThat(causeChainContains(error.getThrowableProxy(), "broker unreachable"))
        .as("the first failure is the cause, not the retry's duplicate key").isTrue();

    // ---- and the file is not completed with chunk 1 missing ----
    verify(fileMetadataRepository, never()).updateChunkingResult(any(), any(), anyInt(), anyDouble(), anyBoolean(),
        anyBoolean(), any());
    assertThat(pipelineEventsSent()).as("pipeline events sent")
        .doesNotContain(PipelineEventEnum.CHUNKING_COMPLETE);
  }

  @Test
  void aChunkThatFailsOnce_isUploadedByCleanupsRetry_andTheFileCompletes() throws Exception {
    // ---- Arrange: one GCS blip on chunk 1's audio ----
    AtomicBoolean failedOnce = new AtomicBoolean();
    when(gcsStorageService.uploadChunk(anyString(), any(Path.class))).thenAnswer(invocation -> {
      Path chunk = invocation.getArgument(1);
      if (chunk.getFileName().toString().equals("audio_chunk_001.wav") && failedOnce.compareAndSet(false, true)) {
        throw new IOException("GCS blip");
      }
      return "chunks/" + chunk.getFileName();
    });

    // ---- Act ----
    runTheJob();

    // ---- Assert: the file completes with all three chunks; the blip is a WARN, not an error ----
    assertThat(failedOnce).as("the blip happened").isTrue();
    assertThat(linesAt(Level.ERROR)).as("ERROR lines from our code").isEmpty();
    assertThat(linesAt(Level.WARN)).as("WARN lines")
        .anySatisfy(warning -> assertThat(warning.getFormattedMessage()).contains("audio_chunk_001.wav"));
    verify(fileMetadataRepository).updateChunkingResult(eq(fileId), eq(FileStatusEnum.CHUNKED), eq(3), anyDouble(),
        eq(true), eq(true), eq("h264"));
  }

  private void runTheJob() throws Exception {
    consumer.consume(FileIngestionEventDTO.builder().fileId(fileId).objectName(objectName).userId("user-1").build());
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
    pool.shutdown();
    assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).as("the job finished").isTrue();
  }

  private List<PipelineEventEnum> pipelineEventsSent() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(kafkaTemplate, atLeastOnce()).send(any(), any(), sent.capture());
    return sent.getAllValues().stream()
        .filter(PipelineEventDTO.class::isInstance)
        .map(event -> ((PipelineEventDTO) event).getEventType())
        .toList();
  }

  private List<ILoggingEvent> linesAt(Level level) {
    return captured.list.stream().filter(logged -> logged.getLevel() == level).toList();
  }

  private static MediaChunk withId(MediaChunk chunk) {
    chunk.setId(UUID.randomUUID());
    return chunk;
  }

  /** The logged exception may be a wrapper; the original is somewhere in its causes. */
  private static boolean causeChainContains(IThrowableProxy proxy, String message) {
    for (IThrowableProxy current = proxy; current != null; current = current.getCause()) {
      if (message.equals(current.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static boolean runs(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Redirect.DISCARD)
          .start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
