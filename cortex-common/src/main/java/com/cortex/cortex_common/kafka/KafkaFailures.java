package com.cortex.cortex_common.kafka;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.MDC;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import lombok.extern.slf4j.Slf4j;

/**
 * The report step of a service's Kafka boundary (ADR-0014): how a failed record is logged. Shared so
 * every service reports a failed record the same way.
 *
 * <p>Only the report lives here. What to retry, how often, and what happens after the last attempt
 * (skip, dead-letter topic) is each service's decision, made where it builds its
 * {@code DefaultErrorHandler} from this and Spring Kafka's own parts. No beans, no state.
 */
@Slf4j
public final class KafkaFailures {

  private KafkaFailures() {
  }

  /**
   * Logs a failed record once at ERROR, with its fileId in MDC so the log pattern prints it, and
   * returns. Nothing else is done to the record, so the error handler moves on to the next one.
   *
   * @param fileIdOf finds the fileId in a record's value — each service knows its own DTO. Gets
   *     {@code null} for a record that could not be read, and may return {@code null}.
   */
  public static ConsumerRecordRecoverer reportAndSkip(Function<Object, UUID> fileIdOf) {
    return (record, exception) -> {
      Map<String, String> previous = MDC.getCopyOfContextMap();

      UUID fileId = fileIdOf(fileIdOf, record.value());
      if (fileId != null) {
        MDC.put("fileId", fileId.toString());
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
    };
  }

  private static UUID fileIdOf(Function<Object, UUID> fileIdOf, Object value) {
    try {
      return fileIdOf.apply(value);
    } catch (RuntimeException e) {
      // Swallowed on purpose: the record must still be reported. A recoverer that throws makes
      // Spring Kafka deliver the record again, which would loop on it forever.
      return null;
    }
  }
}
