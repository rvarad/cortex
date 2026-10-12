package com.cortex.cortex_rag_orchestration.service;

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

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.PipelineEventEnum;

/**
 * EMBEDDING_COMPLETE is the event ingestion counts to decide a file is finished. If its send fails
 * and nobody looks, the chunk is embedded but never counted, and the file stays PROCESSING with
 * nothing in any log (ADR-0014).
 *
 * <p>Checks that a failed send makes the listener method throw, so the Kafka boundary reports it.
 * Calls the listener method directly: the boundary's own behaviour is covered by
 * MediaChunkListenerBoundaryTest.
 */
class OrchestrationServiceSendTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

  private final OrchestrationService orchestrationService = new OrchestrationService(
      kafkaTemplate, mock(VisionService.class), mock(TranscriptionService.class), mock(EmbeddingService.class));

  @BeforeEach
  void failOnlyTheEmbeddingCompleteSend() {
    // Normally injected from application.properties.
    ReflectionTestUtils.setField(orchestrationService, "pipelineEventsTopic", "cortex.pipeline.events");

    when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> {
      PipelineEventDTO event = invocation.getArgument(2);
      return event.getEventType() == PipelineEventEnum.EMBEDDING_COMPLETE
          ? CompletableFuture.failedFuture(new KafkaException("broker unreachable"))
          : CompletableFuture.completedFuture(null);
    });
  }

  @Test
  void failedEmbeddingCompleteSend_makesTheListenerFail() {
    // No video and no audio: the chunk goes straight to embedding, then EMBEDDING_COMPLETE.
    ChunkUploadedEventDTO chunk = ChunkUploadedEventDTO.builder()
        .fileId(UUID.randomUUID())
        .chunkId(UUID.randomUUID())
        .chunkIndex(0)
        .userId("user-1")
        .build();

    assertThatThrownBy(() -> orchestrationService.processMediaChunk(chunk))
        .hasRootCauseMessage("broker unreachable");
  }
}
