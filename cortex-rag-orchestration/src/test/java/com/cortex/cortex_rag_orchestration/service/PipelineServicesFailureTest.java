package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The services below the media-chunk listener let failures propagate (ADR-0014): the listener's
 * Kafka error handler is the one place a failed chunk is logged. So each of them throws the
 * failure as it is, and logs no ERROR line of its own.
 *
 * <p>VisionService is not here: its Gemini client's {@code models} is a final field, so no fake
 * can reach it. Its change is checked by reading.
 */
class PipelineServicesFailureTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
  }

  @Test
  void aFailedEmbedding_propagatesAsItIs_andLogsNothing() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    IllegalStateException geminiDown = new IllegalStateException("Gemini unavailable");
    when(embeddingModel.embed(anyString())).thenThrow(geminiDown);
    EmbeddingService embeddingService = new EmbeddingService(embeddingModel, mock(MediaChunkRepository.class));

    assertThatThrownBy(() -> embeddingService.generateAndSaveEmbedding("a desk", "hello", UUID.randomUUID(), 0, "en"))
        .isSameAs(geminiDown);
    assertThat(errorLines()).as("ERROR lines").isEmpty();
  }

  @Test
  void aFailedGcsRead_propagatesAsItIs_andLogsNothing() {
    Storage storage = mock(Storage.class);
    StorageException gcsDown = new StorageException(503, "backend unavailable");
    when(storage.reader(any(BlobId.class))).thenThrow(gcsDown);
    GcsStorageService gcsStorageService = gcsStorageService(storage);

    assertThatThrownBy(() -> gcsStorageService.getFileStream("chunks/a/audio_chunk_000.wav")).isSameAs(gcsDown);
    assertThat(errorLines()).as("ERROR lines").isEmpty();
  }

  @Test
  void aMissingObject_isAFailure_withNoLogLine() {
    // storage.get answers null: no such object.
    GcsStorageService gcsStorageService = gcsStorageService(mock(Storage.class));

    assertThatThrownBy(() -> gcsStorageService.getFileSize("chunks/a/audio_chunk_000.wav"))
        .hasMessageContaining("chunks/a/audio_chunk_000.wav");
    assertThat(errorLines()).as("ERROR lines").isEmpty();
  }

  @Test
  void aTranscriptionInterruptedBetweenRetries_keepsTheInterruptFlag() {
    // Every attempt fails, and the thread has been asked to stop (as on shutdown): the sleep
    // between attempts throws at once. The flag must survive the throw.
    GcsStorageService gcsStorageService = mock(GcsStorageService.class);
    when(gcsStorageService.getFileStream(anyString())).thenThrow(new StorageException(503, "backend unavailable"));
    TranscriptionService transcriptionService = new TranscriptionService(mock(RestClient.class), new ObjectMapper(),
        gcsStorageService);

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> transcriptionService.transcribe("chunks/a/audio_chunk_000.wav"))
          .hasRootCauseInstanceOf(InterruptedException.class);
    } finally {
      // Read and clear it, so it doesn't leak into the next test on this thread.
      assertThat(Thread.interrupted()).as("the interrupt flag is kept").isTrue();
    }
  }

  private static GcsStorageService gcsStorageService(Storage storage) {
    GcsStorageService gcsStorageService = new GcsStorageService(storage);
    ReflectionTestUtils.setField(gcsStorageService, "bucketName", "cortex-dev-bucket");
    return gcsStorageService;
  }

  private java.util.List<ILoggingEvent> errorLines() {
    return captured.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
  }
}
