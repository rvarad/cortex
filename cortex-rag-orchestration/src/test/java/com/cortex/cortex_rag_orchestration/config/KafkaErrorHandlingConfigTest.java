package com.cortex.cortex_rag_orchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;

/**
 * Spring Boot gives every KafkaTemplate a LoggingProducerListener, which logs each failed send at
 * ERROR: without the fileId, and with the payload. Our callers already report the failure once, at
 * their boundary (ADR-0014), so this service's config must replace it.
 *
 * <p>No broker is needed: the producer factory connects only when something is sent.
 */
class KafkaErrorHandlingConfigTest {

  @Test
  void theKafkaTemplateDoesNotLogFailedSendsItself() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, SslAutoConfiguration.class))
        .withUserConfiguration(KafkaErrorHandlingConfig.class)
        .run(context -> assertThat(context.getBean(ProducerListener.class))
            .isNotInstanceOf(LoggingProducerListener.class));
  }
}
