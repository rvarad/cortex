package com.cortex.cortex_common.kafka;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits for a Kafka send to finish (ADR-0014). {@code KafkaTemplate.send} returns a future, and a
 * send nobody waits on fails without a trace. Every send goes through {@link #await}, so a failed
 * one throws at the call site and travels up to that code's boundary like any other failure.
 */
public final class KafkaSends {

  /**
   * Longer than the producer's own {@code delivery.timeout.ms} (2 minutes by default), on purpose.
   * The producer retries a send by itself until then; the producer decides when a send has failed,
   * not this. A shorter wait would call a send failed that the producer then delivers anyway.
   */
  static final Duration WAIT = Duration.ofSeconds(150);

  private KafkaSends() {
  }

  /**
   * @return the send's result, once the broker has acknowledged it
   * @throws RuntimeException the send's own exception, unwrapped, when the send failed
   */
  public static <T> T await(CompletableFuture<T> send) {
    return await(send, WAIT);
  }

  static <T> T await(CompletableFuture<T> send, Duration wait) {
    try {
      return send.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      // Unwrapped: ExecutionException only says "the future failed". The cause is the failure.
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Kafka send failed", cause);
    } catch (TimeoutException e) {
      throw new IllegalStateException("Kafka send got no answer within " + wait.toSeconds() + "s", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a Kafka send", e);
    }
  }
}
