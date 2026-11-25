package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;

/**
 * A failed chunk fails its file: the listener marks it FOR_CLEANUP and sends PIPELINE_FAILED, then
 * throws, so the Kafka boundary still reports the record once (ADR-0015). A chunk of
 * a file that is already terminal calls no model.
 *
 * <p>Calls the listener method directly: the boundary's own behaviour is covered by
 * MediaChunkListenerBoundaryTest.
 */
class OrchestrationServiceFailedFileTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

  private final VisionService visionService = mock(VisionService.class);

  private final TranscriptionService transcriptionService = mock(TranscriptionService.class);

  private final EmbeddingService embeddingService = mock(EmbeddingService.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final OrchestrationService orchestrationService = new OrchestrationService(
      kafkaTemplate, visionService, transcriptionService, embeddingService, fileMetadataRepository);

  private final UUID fileId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    // Normally injected from application.properties.
    ReflectionTestUtils.setField(orchestrationService, "pipelineEventsTopic", "cortex.pipeline.events");
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    givenTheFileIs(FileStatusEnum.PROCESSING);
  }

  @Test
  void aFailedVision_marksTheFile_andSendsOnePipelineFailed() {
    failVision();
    when(fileMetadataRepository.markForCleanup(any(), any())).thenReturn(1);

    Throwable thrown = catchThrowable(() -> orchestrationService.processMediaChunk(videoChunk()));

    assertThat(thrown).as("the listener still fails, for the Kafka boundary").isNotNull();
    verify(fileMetadataRepository).markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES);
    List<PipelineEventDTO> failed = pipelineFailedSent();
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).getFileId()).isEqualTo(fileId);
    assertThat(failed.get(0).getMessage()).isEqualTo(PipelineEventEnum.FAILED_BY_ERROR_MESSAGE);
  }

  @Test
  void twoChunksFailing_sendOnePipelineFailed() {
    // Both chunks read PROCESSING before either marks the file; only the first marking moves it.
    failVision();
    when(fileMetadataRepository.markForCleanup(any(), any())).thenReturn(1, 0);

    catchThrowable(() -> orchestrationService.processMediaChunk(videoChunk()));
    catchThrowable(() -> orchestrationService.processMediaChunk(videoChunk()));

    assertThat(pipelineFailedSent()).as("PIPELINE_FAILED events").hasSize(1);
  }

  @Test
  void aMarkingThatThrows_isSuppressed_underTheChunksOwnFailure() {
    failVision();
    when(fileMetadataRepository.markForCleanup(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("database down"));

    Throwable thrown = catchThrowable(() -> orchestrationService.processMediaChunk(videoChunk()));

    assertThat(Stream.iterate(thrown, Objects::nonNull, Throwable::getCause).map(Throwable::getMessage))
        .as("the chunk's own failure is in the thrown chain").contains("Gemini unavailable");
    assertThat(Arrays.stream(thrown.getSuppressed()).map(Throwable::getMessage))
        .as("the marking's failure, suppressed").containsExactly("database down");
    assertThat(pipelineFailedSent()).as("PIPELINE_FAILED events").isEmpty();
  }

  @Test
  void aChunkOfAFailedFile_callsNoModel() {
    givenTheFileIs(FileStatusEnum.FOR_CLEANUP);

    ChunkUploadedEventDTO chunk = videoChunk();
    chunk.setAudioPath("chunks/audio_chunk_000.wav");

    orchestrationService.processMediaChunk(chunk);

    verifyNoInteractions(visionService, transcriptionService, embeddingService, kafkaTemplate);
  }

  @Test
  void aChunkOfAMissingFile_callsNoModel() {
    // Deleted (FOR_DELETION is hidden from JPA, so findById is empty): skipped like a terminal file (ADR-0015).
    when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

    orchestrationService.processMediaChunk(videoChunk());

    verifyNoInteractions(visionService, transcriptionService, embeddingService, kafkaTemplate);
    verify(fileMetadataRepository, never()).markForCleanup(any(), any());
  }

  private void givenTheFileIs(FileStatusEnum status) {
    FileMetadata file = new FileMetadata(); // the builder wants every non-null column; the listener reads only the status
    file.setFileStatus(status);
    when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(file));
  }

  private void failVision() {
    when(visionService.generateDescription(anyString())).thenThrow(new IllegalStateException("Gemini unavailable"));
  }

  private ChunkUploadedEventDTO videoChunk() {
    return ChunkUploadedEventDTO.builder()
        .fileId(fileId)
        .chunkId(UUID.randomUUID())
        .chunkIndex(0)
        .videoPath("chunks/video_chunk_000.mp4")
        .userId("user-1")
        .build();
  }

  /** Every PIPELINE_FAILED sent; none is fine, unlike a captor's verify. */
  private List<PipelineEventDTO> pipelineFailedSent() {
    return mockingDetails(kafkaTemplate).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("send"))
        .map(invocation -> invocation.getArgument(2))
        .filter(PipelineEventDTO.class::isInstance)
        .map(PipelineEventDTO.class::cast)
        .filter(event -> event.getEventType() == PipelineEventEnum.PIPELINE_FAILED)
        .toList();
  }
}
