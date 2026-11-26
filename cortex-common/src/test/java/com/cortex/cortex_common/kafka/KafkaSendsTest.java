package com.cortex.cortex_common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;

/**
 * KafkaSends.await is how every Kafka send in the services waits for its answer. Plain futures stand
 * in for sends: a KafkaTemplate send is one.
 */
class KafkaSendsTest {

  @Test
  void finishedSend_returnsItsResult() {
    assertThat(KafkaSends.await(CompletableFuture.completedFuture("sent"))).isEqualTo("sent");
  }

  @Test
  void failedSend_throwsTheSendsOwnException_unwrapped() {
    // The caller's boundary should log what went wrong with the send, not "the future failed".
    KafkaException failure = new KafkaException("broker unreachable");

    assertThatThrownBy(() -> KafkaSends.await(CompletableFuture.failedFuture(failure))).isSameAs(failure);
  }

  @Test
  void failedSendWithACheckedCause_throwsWithThatCause() {
    IOException failure = new IOException("connection reset");

    assertThatThrownBy(() -> KafkaSends.await(CompletableFuture.failedFuture(failure)))
        .isInstanceOf(IllegalStateException.class)
        .hasCause(failure);
  }

  @Test
  void sendWithNoAnswer_throwsAfterTheWait() {
    // The real wait is 150 s; a short one here, so the test doesn't take minutes.
    CompletableFuture<String> neverAnswers = new CompletableFuture<>();

    assertThatThrownBy(() -> KafkaSends.await(neverAnswers, Duration.ofMillis(100)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  void theWait_isLongerThanTheProducersOwnDeliveryTimeout() {
    // The producer retries a send for up to delivery.timeout.ms (2 minutes by default) before it
    // reports failure. A shorter wait would call a send failed that the producer delivers anyway.
    assertThat(KafkaSends.WAIT).isGreaterThan(Duration.ofMinutes(2));
  }

  @Test
  void interruptedWait_putsTheInterruptFlagBack() throws InterruptedException {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean flagAfterwards = new AtomicBoolean();

    Thread waiter = Thread.ofPlatform().start(() -> {
      Thread.currentThread().interrupt(); // interrupted before it even starts waiting
      try {
        KafkaSends.await(new CompletableFuture<>(), Duration.ofSeconds(10));
      } catch (RuntimeException e) {
        thrown.set(e);
      }
      flagAfterwards.set(Thread.currentThread().isInterrupted());
    });
    waiter.join();

    assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
    assertThat(flagAfterwards.get()).isTrue();
  }
}
