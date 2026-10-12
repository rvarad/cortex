package com.cortex.cortex_rag_orchestration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.kafka.KafkaFailures;

/**
 * The Kafka boundary for this service (ADR-0014): the one place a failed record is handled.
 *
 * <p>Spring Boot hands this bean to every listener container. A record that throws — or that could
 * not be read at all, which ErrorHandlingDeserializer turns into an ordinary failure — is reported
 * once by {@link KafkaFailures} and skipped, so the next record is consumed. The report is shared;
 * the policy (retries, what happens after the last attempt) is this service's, and lives here.
 */
@Configuration
public class KafkaErrorHandlingConfig {

  @Bean
  public CommonErrorHandler kafkaErrorHandler() {
    // Zero retries, on purpose (ADR-0014). Spring Kafka's default is nine immediate retries, and
    // each one calls Gemini and Groq again for the same chunk. Retries come back with backoff once
    // the logs show which failures pass on their own.
    return new DefaultErrorHandler(
        KafkaFailures.reportAndSkip(value -> value instanceof ChunkUploadedEventDTO chunk ? chunk.getFileId() : null),
        new FixedBackOff(0L, 0L));
  }
}
