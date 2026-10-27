package com.cortex.cortex_media_processing_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.kafka.KafkaFailures;

/**
 * The Kafka boundary for this service (ADR-0014): the one place a failed record is handled.
 *
 * <p>The media listener only hands the job to the executor, so a failure here is the handoff
 * itself, or a record that could not be read. The job's own failures end at its BackgroundTask, not
 * here. Reported once by {@link KafkaFailures} and skipped; the policy is this service's, and lives
 * here.
 */
@Configuration
public class KafkaErrorHandlingConfig {

  @Bean
  public CommonErrorHandler kafkaErrorHandler() {
    // Zero retries, on purpose (ADR-0014): retries come back once the logs show which failures pass
    // on their own.
    return new DefaultErrorHandler(
        KafkaFailures.reportAndSkip(value -> value instanceof FileIngestionEventDTO event ? event.getFileId() : null),
        new FixedBackOff(0L, 0L));
  }

  /**
   * Replaces Spring Boot's LoggingProducerListener, which logs every failed send at ERROR on the
   * producer's thread: without the fileId, and with the payload. The caller already gets the
   * failure from KafkaSends.await, and its boundary logs it once (ADR-0014).
   */
  @Bean
  public ProducerListener<Object, Object> kafkaProducerListener() {
    return new ProducerListener<>() {
    };
  }
}
