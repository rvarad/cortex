package com.cortex.cortex_rag_orchestration.config;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * The Kafka boundary for this service (ADR-0014): the one place a failed record is reported.
 *
 * <p>Spring Boot hands this bean to every listener container. A record that throws — or that could
 * not be read at all, which ErrorHandlingDeserializer turns into an ordinary failure — ends here as
 * one ERROR line with the record's fileId, and is skipped so the next record is consumed.
 */
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

  @Bean
  public CommonErrorHandler kafkaErrorHandler() {
    // Zero retries, on purpose (ADR-0014). Spring Kafka's default is nine immediate retries, and
    // each one calls Gemini and Groq again for the same chunk. Retries come back with backoff once
    // the logs show which failures pass on their own.
    return new DefaultErrorHandler(KafkaErrorHandlingConfig::reportAndSkip, new FixedBackOff(0L, 0L));
  }

  /**
   * Logs the failed record once, with its fileId in MDC so the log pattern prints it. The listener
   * put the fileId in MDC itself, but removed it before the error reached here.
   */
  static void reportAndSkip(ConsumerRecord<?, ?> record, Exception exception) {
    Map<String, String> previous = MDC.getCopyOfContextMap();

    // Null for a record that could not be read: there is no DTO, so no fileId to give.
    if (record.value() instanceof ChunkUploadedEventDTO chunk && chunk.getFileId() != null) {
      MDC.put("fileId", chunk.getFileId().toString());
    }

    try {
      log.error("Kafka record failed and was skipped: topic={} partition={} offset={}",
          record.topic(), record.partition(), record.offset(), exception);
    } finally {
      if (previous == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
  }
}
