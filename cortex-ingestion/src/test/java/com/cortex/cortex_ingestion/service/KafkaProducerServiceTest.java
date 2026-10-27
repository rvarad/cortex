package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.PipelineEventEnum;

/**
 * A Kafka send isn't finished when send() returns: it hands back a future, and the result comes
 * later. If nobody looks at it, a failed send leaves no trace — the webhook marks the file UPLOADED,
 * the "file ingested" event never reaches media, and the file sits there forever (ADR-0014).
 *
 * <p>These check that a failed send now throws from the call, so it reaches the caller's boundary.
 * The KafkaTemplate is a fake whose sends fail the way a producer reports an unreachable broker.
 */
class KafkaProducerServiceTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

  private final KafkaProducerService kafkaProducerService = new KafkaProducerService(kafkaTemplate);

  @BeforeEach
  void setTopicsAndFailEverySend() {
    // Normally injected from application.properties.
    ReflectionTestUtils.setField(kafkaProducerService, "mediaTopic", "cortex.file.ingested.media");
    ReflectionTestUtils.setField(kafkaProducerService, "documentTopic", "cortex.file.ingested.document");
    ReflectionTestUtils.setField(kafkaProducerService, "pipelineEventsTopic", "cortex.pipeline.events");

    when(kafkaTemplate.send(any(), any(), any()))
        .thenAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("broker unreachable")));
  }

  @Test
  void failedFileIngestedSend_throwsFromTheCall() {
    FileIngestionEventDTO event = FileIngestionEventDTO.builder()
        .fileId(UUID.randomUUID())
        .contentType("video/mp4")
        .build();

    assertThatThrownBy(() -> kafkaProducerService.sendFileIngestedEvent(event))
        .hasMessageContaining("broker unreachable");
  }

  @Test
  void failedPipelineEventSend_throwsFromTheCall() {
    PipelineEventDTO event = PipelineEventDTO.builder()
        .fileId(UUID.randomUUID())
        .eventType(PipelineEventEnum.PIPELINE_STARTED)
        .message("File uploaded successfully")
        .build();

    assertThatThrownBy(() -> kafkaProducerService.sendPipelineEvent(event))
        .hasMessageContaining("broker unreachable");
  }
}
