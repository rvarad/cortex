package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

/**
 * The media-chunk-uploaded listener is a Kafka boundary (ADR-0014). When a record fails, the
 * service's Kafka error handler is the one place that reports it: one ERROR line with the fileId,
 * no retries for now, and the next record is still consumed.
 *
 * <p>Runs the real listener on an embedded Kafka broker, with only Kafka's auto-configuration and
 * the listener in the context. The AI services are fakes. The error handler is picked up by name
 * through a component scan, so this test compiles — and fails — before that class exists: with no
 * handler of ours, Spring Kafka's default one runs, which retries nine times.
 */
@SpringBootTest(
    classes = { OrchestrationService.class, MediaChunkListenerBoundaryTest.KafkaErrorHandling.class },
    properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@ImportAutoConfiguration({ KafkaAutoConfiguration.class, SslAutoConfiguration.class })
@EmbeddedKafka(partitions = 1, topics = { "media-chunk-uploaded", "cortex.pipeline.events" })
class MediaChunkListenerBoundaryTest {

  /** Finds the service's Kafka error handler config by name, if it exists, and nothing else. */
  @Configuration
  @ComponentScan(basePackages = "com.cortex.cortex_rag_orchestration.config", useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.KafkaErrorHandlingConfig"))
  static class KafkaErrorHandling {
  }

  private static final String TOPIC = "media-chunk-uploaded";

  @MockitoBean
  private VisionService visionService;

  @MockitoBean
  private TranscriptionService transcriptionService;

  @MockitoBean
  private EmbeddingService embeddingService;

  @Autowired
  private KafkaTemplate<String, Object> kafkaTemplate;

  @Autowired
  private EmbeddedKafkaBroker broker;

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /** Thread-safe: events arrive on the listener's thread. Frozen on arrival for their MDC. */
  private final List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();

  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      captured.add(event);
    }
  };

  @BeforeEach
  void attachAppender() {
    // After the context has started: Spring Boot resets logging while it starts, which would drop
    // an appender attached earlier.
    appender.start();
    cortexLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    cortexLogger.detachAppender(appender);
  }

  @Test
  void aFailingRecord_isReportedOnceWithItsFileId_isNotRetried_andTheNextRecordIsConsumed() {
    // ---- Arrange: vision fails for the first chunk; the second chunk has no video or audio ----
    UUID failingFileId = UUID.randomUUID();
    UUID nextChunkId = UUID.randomUUID();

    when(visionService.generateDescription(anyString()))
        .thenThrow(new IllegalStateException("Gemini unavailable"));

    // ---- Act ----
    kafkaTemplate.send(TOPIC, chunk(failingFileId, UUID.randomUUID(), "chunks/a/video_chunk_000.mp4"));
    kafkaTemplate.send(TOPIC, chunk(UUID.randomUUID(), nextChunkId, null));

    // The next record was consumed: with nothing to analyse, it goes straight to embedding.
    verify(embeddingService, timeout(30_000))
        .generateAndSaveEmbedding(any(), any(), eq(nextChunkId), anyInt(), any());

    // ---- Assert ----
    verify(visionService, times(1).description("Gemini is called once: no retries"))
        .generateDescription(anyString());

    List<ILoggingEvent> errors = errors();
    assertThat(errors).as("ERROR lines from our code").hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", failingFileId.toString());
    assertThat(causeChainContains(errors.get(0).getThrowableProxy(), "Gemini unavailable"))
        .as("the original failure is in the logged stack trace").isTrue();
  }

  @Test
  void aRecordThatIsNotValidJson_isReportedOnce_andTheNextRecordIsConsumed() throws Exception {
    // ---- Arrange: a producer that writes plain text, not JSON ----
    KafkaTemplate<String, String> textTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
        KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer()));
    UUID nextChunkId = UUID.randomUUID();

    // ---- Act ----
    // Waited for, so the text record is in the topic before the valid one. Kafka keeps order only
    // within one producer: sent without waiting, the valid record (other producer) landed first,
    // and the test checked for the error before the text record had even been read.
    textTemplate.send(TOPIC, "this is not json").get(10, TimeUnit.SECONDS);
    kafkaTemplate.send(TOPIC, chunk(UUID.randomUUID(), nextChunkId, null));

    verify(embeddingService, timeout(30_000))
        .generateAndSaveEmbedding(any(), any(), eq(nextChunkId), anyInt(), any());

    // ---- Assert ----
    assertThat(errors()).as("ERROR lines from our code").hasSize(1);

    textTemplate.destroy();
  }

  private static ChunkUploadedEventDTO chunk(UUID fileId, UUID chunkId, String videoPath) {
    return ChunkUploadedEventDTO.builder()
        .fileId(fileId)
        .chunkId(chunkId)
        .chunkIndex(0)
        .videoPath(videoPath)
        .userId("user-1")
        .build();
  }

  private List<ILoggingEvent> errors() {
    return captured.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }

  /** The logged exception is the listener's wrapper; the original is somewhere in its causes. */
  private static boolean causeChainContains(IThrowableProxy proxy, String message) {
    for (IThrowableProxy current = proxy; current != null; current = current.getCause()) {
      if (message.equals(current.getMessage())) {
        return true;
      }
    }
    return false;
  }
}
