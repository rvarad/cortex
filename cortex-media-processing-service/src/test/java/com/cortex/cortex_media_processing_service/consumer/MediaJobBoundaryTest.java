package com.cortex.cortex_media_processing_service.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
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
import ch.qos.logback.core.read.ListAppender;

/**
 * A media job is a fire-and-forget task: the Kafka listener hands it to the executor and returns
 * (ADR-0014). So when a job fails, nothing above it will ever hear about it — the boundary at the
 * top of the job is the only place the failure can be reported.
 *
 * <p>This drives the real path — consumer, the real executor bean, the real MediaProcessingService
 * — with GCS faked to throw on the job's first call, and checks the failure ends in exactly one
 * ERROR line, from the boundary, carrying the fileId, with nothing escaping onto the pool thread.
 */
class MediaJobBoundaryTest {

  /** Every logger of ours sits under "com.cortex", so this sees all of our ERROR lines. */
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

  @SuppressWarnings("unchecked")
  private final MediaProcessingService mediaProcessingService = new MediaProcessingService(
      gcsStorageService,
      mock(MediaChunkRepository.class),
      mock(FileMetadataRepository.class),
      mock(KafkaTemplate.class),
      new ObjectMapper());

  private final ThreadPoolTaskExecutor executor = new MediaProcessingConfig().mediaProcessingExecutor();

  private final MediaEventConsumer consumer = new MediaEventConsumer(executor, mediaProcessingService);

  /**
   * The pool's threads have no handler of their own, so anything thrown out of a job lands in the
   * JVM-wide default one. Swapped for a recorder during each test, then put back.
   */
  private Thread.UncaughtExceptionHandler originalDefaultHandler;

  private final AtomicReference<Throwable> escaped = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);

    originalDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> escaped.set(throwable));
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
    Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler);
    executor.shutdown();
    MDC.clear();
  }

  @Test
  void aFailingJob_isLoggedOnceAtItsBoundary_withTheFileId_andNothingEscapesOntoThePoolThread()
      throws Exception {

    // ---- Arrange: GCS fails on the job's very first call ----
    UUID fileId = UUID.randomUUID();
    when(gcsStorageService.getPresignedUrl(anyString()))
        .thenThrow(new IllegalStateException("GCS unreachable"));

    FileIngestionEventDTO event = FileIngestionEventDTO.builder()
        .fileId(fileId)
        .objectName("uploads/media/" + fileId + ".mp4")
        .userId("user-1")
        .build();

    // ---- Act: the listener hands off and returns; then wait for the job itself to finish ----
    consumer.consume(event);

    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("the job finished").isTrue();

    // ---- Assert ----
    List<ILoggingEvent> errors = captured.list.stream()
        .filter(logged -> logged.getLevel() == Level.ERROR)
        .toList();

    assertThat(errors).as("ERROR lines from our code").hasSize(1);

    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary").isEqualTo(BackgroundTask.class.getName());
    assertThat(error.getThrowableProxy()).isNotNull();
    assertThat(error.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("GCS unreachable");
    assertThat(error.getMDCPropertyMap()).containsEntry("fileId", fileId.toString());

    assertThat(escaped.get()).as("nothing escapes onto the pool thread").isNull();
  }
}
