package com.cortex.cortex_common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * KafkaFailures.reportAndSkip is the report step of every service's Kafka boundary. These check it
 * without a broker: a ConsumerRecord is a plain object, so the recoverer is called directly, the way
 * Spring Kafka's error handler calls it.
 */
class KafkaFailuresTest {

  /** A stand-in for a service's DTO, so these tests don't depend on any real one. */
  record Event(UUID fileId) {
  }

  private static final Function<Object, UUID> FILE_ID_OF_EVENT =
      value -> value instanceof Event event ? event.fileId() : null;

  private final Logger logger = (Logger) LoggerFactory.getLogger(KafkaFailures.class);

  /** Freezes each event as it arrives: logback reads MDC lazily, from whoever asks first. */
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  @BeforeEach
  void attachAppender() {
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppenderAndClearMdc() {
    logger.detachAppender(captured);
    MDC.clear();
  }

  @Test
  void failedRecord_isLoggedOnceAtError_withItsFileIdAndWhereItWas() {
    UUID fileId = UUID.randomUUID();
    ConsumerRecord<String, Object> record = new ConsumerRecord<>("media-chunk-uploaded", 2, 41L, "key",
        new Event(fileId));
    IllegalStateException failure = new IllegalStateException("Gemini unavailable");

    KafkaFailures.reportAndSkip(FILE_ID_OF_EVENT).accept(record, failure);

    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);

    ILoggingEvent error = errors.get(0);
    assertThat(error.getFormattedMessage()).contains("topic=media-chunk-uploaded", "partition=2", "offset=41");
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("Gemini unavailable");
    assertThat(error.getMDCPropertyMap()).containsEntry("fileId", fileId.toString());
  }

  @Test
  void recordThatCouldNotBeRead_isLoggedWithoutAFileId() {
    // ErrorHandlingDeserializer hands over a null value when the bytes could not be read.
    ConsumerRecord<String, Object> record = new ConsumerRecord<>("cortex.pipeline.events", 0, 7L, null, null);

    KafkaFailures.reportAndSkip(FILE_ID_OF_EVENT).accept(record, new IllegalStateException("not JSON"));

    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).doesNotContainKey("fileId");
  }

  @Test
  void fileIdLookupThatThrows_doesNotStopTheReport() {
    // A recoverer that throws makes Spring Kafka deliver the record again — forever. So a broken
    // lookup must cost the fileId, never the report.
    ConsumerRecordRecoverer recoverer = KafkaFailures.reportAndSkip(value -> {
      throw new ClassCastException("not the DTO this service expected");
    });
    ConsumerRecord<String, Object> record = new ConsumerRecord<>("cortex.pipeline.events", 0, 7L, null,
        new Event(UUID.randomUUID()));

    assertThatCode(() -> recoverer.accept(record, new IllegalStateException("boom"))).doesNotThrowAnyException();

    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).doesNotContainKey("fileId");
  }

  @Test
  void callersMdc_isLeftAsItWas() {
    // The report runs on the listener's thread; the fileId it adds must not stay behind for the
    // next record.
    MDC.put("conversationId", "already-here");
    ConsumerRecord<String, Object> record = new ConsumerRecord<>("media-chunk-uploaded", 0, 1L, null,
        new Event(UUID.randomUUID()));

    KafkaFailures.reportAndSkip(FILE_ID_OF_EVENT).accept(record, new IllegalStateException("boom"));

    assertThat(MDC.get("fileId")).isNull();
    assertThat(MDC.get("conversationId")).isEqualTo("already-here");
  }

  private List<ILoggingEvent> errorEvents() {
    return captured.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }
}
