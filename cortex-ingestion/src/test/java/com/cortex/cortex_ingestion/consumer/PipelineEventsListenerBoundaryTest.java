package com.cortex.cortex_ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_ingestion.service.PipelineEventsService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

/**
 * The cortex.pipeline.events listener is ingestion's Kafka boundary (ADR-0014). A record that fails
 * is reported once, with its fileId, not retried, and the next record is still consumed. A record
 * that cannot be read is reported once and skipped, instead of stopping the listener.
 *
 * <p>Runs the real listener on an embedded Kafka broker, with only Kafka's auto-configuration and
 * the listener in the context; the service behind it is a fake. The error handler is picked up by
 * name through a component scan, so this test compiles — and fails — before that class exists.
 */
@SpringBootTest(
    classes = { PipelineEventsConsumer.class, PipelineEventsListenerBoundaryTest.KafkaErrorHandling.class },
    properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@ImportAutoConfiguration({ KafkaAutoConfiguration.class, SslAutoConfiguration.class })
@EmbeddedKafka(partitions = 1, topics = { PipelineEventsListenerBoundaryTest.TOPIC })
class PipelineEventsListenerBoundaryTest {

  /** Finds the service's Kafka error handler config by name, if it exists, and nothing else. */
  @Configuration
  @ComponentScan(basePackages = "com.cortex.cortex_ingestion.config", useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.KafkaErrorHandlingConfig"))
  static class KafkaErrorHandling {
  }

  static final String TOPIC = "cortex.pipeline.events";

  @MockitoBean
  private PipelineEventsService pipelineEventsService;

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
    // After the context has started: Spring Boot resets logging while it starts.
    appender.start();
    cortexLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    cortexLogger.detachAppender(appender);
  }

  @Test
  void aFailingRecord_isReportedOnceWithItsFileId_isNotRetried_andTheNextRecordIsConsumed() {
    // ---- Arrange: saving the first file's event fails, as if the database were down ----
    UUID failingFileId = UUID.randomUUID();
    UUID nextFileId = UUID.randomUUID();

    doThrow(new IllegalStateException("database unavailable"))
        .when(pipelineEventsService).processNewEvent(argThat(event -> failingFileId.equals(event.getFileId())));

    // ---- Act ----
    kafkaTemplate.send(TOPIC, failingFileId.toString(), event(failingFileId));
    kafkaTemplate.send(TOPIC, nextFileId.toString(), event(nextFileId));

    verify(pipelineEventsService, timeout(30_000))
        .processNewEvent(argThat(event -> nextFileId.equals(event.getFileId())));

    // ---- Assert ----
    verify(pipelineEventsService, times(1).description("the failing event is tried once: no retries"))
        .processNewEvent(argThat(event -> failingFileId.equals(event.getFileId())));

    List<ILoggingEvent> errors = errors();
    assertThat(errors).as("ERROR lines from our code").hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", failingFileId.toString());
    assertThat(causeChainContains(errors.get(0).getThrowableProxy(), "database unavailable"))
        .as("the original failure is in the logged stack trace").isTrue();
  }

  @Test
  void aRecordThatIsNotValidJson_isReportedOnce_andTheNextRecordIsConsumed() throws Exception {
    // ---- Arrange: a producer that writes plain text, not JSON ----
    KafkaTemplate<String, String> textTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
        KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer()));
    UUID nextFileId = UUID.randomUUID();

    // ---- Act ----
    // Waited for, so the text record is in the topic before the valid one: Kafka keeps order only
    // within one producer.
    textTemplate.send(TOPIC, "this is not json").get(10, TimeUnit.SECONDS);
    kafkaTemplate.send(TOPIC, nextFileId.toString(), event(nextFileId));

    verify(pipelineEventsService, timeout(30_000))
        .processNewEvent(argThat(event -> nextFileId.equals(event.getFileId())));

    // ---- Assert ----
    assertThat(errors()).as("ERROR lines from our code").hasSize(1);

    textTemplate.destroy();
  }

  private static PipelineEventDTO event(UUID fileId) {
    return PipelineEventDTO.builder()
        .fileId(fileId)
        .eventType(PipelineEventEnum.EMBEDDING_COMPLETE)
        .message("Embedding complete")
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
