package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.kafka.CompletionTriggerLostException;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;

/**
 * What a failed send does depends on its kind (ADR-0014, 2026-10-30 amendment).
 *
 * <p>EMBEDDING_COMPLETE is the event ingestion counts to decide a file is finished. If its send
 * fails and nobody looks, the chunk is embedded but never counted, and the file stays PROCESSING
 * with nothing in any log. So a failed send makes the listener method throw a lost-trigger
 * exception, and the Kafka boundary reports it.
 *
 * <p>A glass box event only feeds the timeline. If its send fails, the chunk is still embedded.
 *
 * <p>Calls the listener method directly: the boundary's own behaviour is covered by
 * MediaChunkListenerBoundaryTest.
 */
class OrchestrationServiceSendTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

  private final VisionService visionService = mock(VisionService.class);

  private final EmbeddingService embeddingService = mock(EmbeddingService.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final OrchestrationService orchestrationService = new OrchestrationService(
      kafkaTemplate, visionService, mock(TranscriptionService.class), embeddingService, fileMetadataRepository);

  private final UUID chunkId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    // Normally injected from application.properties.
    ReflectionTestUtils.setField(orchestrationService, "pipelineEventsTopic", "cortex.pipeline.events");
    FileMetadata file = new FileMetadata(); // the builder wants every non-null column; the listener reads only the status
    file.setFileStatus(FileStatusEnum.PROCESSING);
    when(fileMetadataRepository.findById(any())).thenReturn(Optional.of(file));
  }

  @Test
  void failedEmbeddingCompleteSend_makesTheListenerFail() {
    failOnlyTheSendOf(PipelineEventEnum.EMBEDDING_COMPLETE);

    // No video and no audio: the chunk goes straight to embedding, then EMBEDDING_COMPLETE.
    ChunkUploadedEventDTO chunk = chunk().build();

    Throwable thrown = catchThrowable(() -> orchestrationService.processMediaChunk(chunk));

    // A lost trigger, with the send's own exception under it. The Kafka report logs this chain
    // once at ERROR, as MediaChunkListenerBoundaryTest shows for any exception the listener throws.
    Throwable trigger = Stream.iterate(thrown, Objects::nonNull, Throwable::getCause)
        .filter(CompletionTriggerLostException.class::isInstance)
        .findFirst()
        .orElse(null);
    assertThat(trigger).as("a CompletionTriggerLostException in the thrown chain").isNotNull();
    assertThat(trigger.getCause()).isInstanceOf(KafkaException.class).hasMessage("broker unreachable");

    // Not failed: the chunk is embedded, and the sweep completes the file (failed-states, Decisions).
    verify(fileMetadataRepository, never()).markForCleanup(any(), any());
    assertThat(pipelineEventsSent()).as("pipeline events sent").doesNotContain(PipelineEventEnum.PIPELINE_FAILED);
  }

  @Test
  void failedVisionAnalysisStartedSend_stillEmbedsTheChunk_andSendsEmbeddingComplete() {
    failOnlyTheSendOf(PipelineEventEnum.VISION_ANALYSIS_STARTED);
    when(visionService.generateDescription(anyString())).thenReturn("A slide with a chart");

    orchestrationService.processMediaChunk(chunk().videoPath("chunks/video_chunk_000.mp4").build());

    verify(embeddingService).generateAndSaveEmbedding(eq("A slide with a chart"), any(), eq(chunkId), eq(0), any());
    assertThat(pipelineEventsSent()).as("pipeline events sent").contains(PipelineEventEnum.EMBEDDING_COMPLETE);
  }

  private void failOnlyTheSendOf(PipelineEventEnum failing) {
    when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> {
      PipelineEventDTO event = invocation.getArgument(2);
      return event.getEventType() == failing
          ? CompletableFuture.failedFuture(new KafkaException("broker unreachable"))
          : CompletableFuture.completedFuture(null);
    });
  }

  private ChunkUploadedEventDTO.ChunkUploadedEventDTOBuilder chunk() {
    return ChunkUploadedEventDTO.builder()
        .fileId(UUID.randomUUID())
        .chunkId(chunkId)
        .chunkIndex(0)
        .userId("user-1");
  }

  private List<PipelineEventEnum> pipelineEventsSent() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(kafkaTemplate, atLeastOnce()).send(any(), any(), sent.capture());
    return sent.getAllValues().stream().map(event -> ((PipelineEventDTO) event).getEventType()).toList();
  }
}
