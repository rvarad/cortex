package com.cortex.cortex_ingestion.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.model.PlaybackStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.model.PipelineEvent;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineEventsService {

  private final SseEmitterRegistry sseEmitterRegistry;

  private final PipelineEventRepository pipelineEventRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final MediaChunkRepository mediaChunkRepository;

  private final ObjectMapper objectMapper;

  public SseEmitter subscribeToEvents(UUID fileId, String userId) {
    log.info("[PipelineEventsService] Client subscribed to events for fileId: {}", fileId);

    FileMetadata file = fileMetadataRepository.findByIdAndUserId(fileId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    SseEmitter emitter = new SseEmitter(0L);

    sendPreviousEvents(fileId, emitter);

    if (file.getFileStatus() == FileStatusEnum.COMPLETED) {
      log.info("[PipelineEventsService] File is already completed. Closing emitter for fileId: {}", fileId);
      emitter.complete();
    } else if (file.getFileStatus() == FileStatusEnum.REJECTED || file.getFileStatus() == FileStatusEnum.FAILED) {
      log.info("[PipelineEventsService] File has been rejected. Closing emitter for fileId: {}", fileId);
      emitter.complete();
    } else {
      sseEmitterRegistry.addEmitter(fileId, emitter);
    }

    return emitter;
  }

  private void sendPreviousEvents(UUID fileId, SseEmitter emitter) {
    List<PipelineEvent> historicalEvents = pipelineEventRepository.findByFileIdOrderByCreatedAtAsc(fileId);

    for (PipelineEvent event : historicalEvents) {
      try {
        Map<String, Object> eventMap = event.getMetadata() != null
            ? objectMapper.readValue(event.getMetadata(), new TypeReference<>() {
            })
            : Map.of();

        PipelineEventDTO eventDTO = PipelineEventDTO.builder().fileId(event.getFileId())
            .chunkId(event.getChunkId())
            .chunkIndex(event.getChunkIndex())
            .eventType(event.getEventType())
            .message(event.getMessage())
            .metadata(eventMap)
            .build();

        emitter.send(SseEmitter.event().name(event.getEventType().name()).data(eventDTO));
      } catch (Exception e) {
        log.error("Failed to send historical event for fileId {}", fileId, e);
      }
    }
  }

  @Transactional
  public void processNewEvent(PipelineEventDTO eventDTO) {
    try {
      String metaData = eventDTO.getMetadata() != null ? objectMapper.writeValueAsString(eventDTO.getMetadata()) : null;

      PipelineEvent event = PipelineEvent.builder()
          .fileId(eventDTO.getFileId())
          .chunkId(eventDTO.getChunkId())
          .chunkIndex(eventDTO.getChunkIndex())
          .eventType(eventDTO.getEventType())
          .message(eventDTO.getMessage())
          .metadata(metaData)
          .build();

      pipelineEventRepository.save(event);

      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        public void afterCommit() {
          sseEmitterRegistry.broadcast(eventDTO);

          if (eventDTO.getEventType() == PipelineEventEnum.UPLOAD_REJECTED
              || eventDTO.getEventType() == PipelineEventEnum.PIPELINE_FAILED)
            sseEmitterRegistry.completeEmitters(eventDTO.getFileId());
        }
      });

      if (eventDTO.getEventType() == PipelineEventEnum.CHUNKING_COMPLETE
          || eventDTO.getEventType() == PipelineEventEnum.EMBEDDING_COMPLETE
          || eventDTO.getEventType() == PipelineEventEnum.NORMALISATION_COMPLETE
          || eventDTO.getEventType() == PipelineEventEnum.NORMALISATION_FAILED) {
        checkPipelineCompletion(eventDTO.getFileId());
      }

    } catch (JsonProcessingException e) {
      log.error("Failed to process new event for fileId {}", eventDTO.getFileId(), e);
    }

  }

  private void checkPipelineCompletion(UUID fileId) throws JsonProcessingException {

    FileMetadata file = fileMetadataRepository.findByIdForUpdate(fileId).orElse(null);

    if (file == null) {
      log.warn("[PipelineEventsService] File Metadata not found for fileId: {}", fileId);
      return;
    }

    if (file.getFileStatus() == FileStatusEnum.COMPLETED)
      return;

    if (file.getTotalChunks() == null || file.getTotalChunks() == 0)
      return;

    if (file.getFileStatus() == FileStatusEnum.CHUNKED) {
      file.setFileStatus(FileStatusEnum.PROCESSING);
      fileMetadataRepository.save(file);
    }

    long completedChunks = mediaChunkRepository.countByFileIdAndStatus(fileId, MediaChunk.Status.COMPLETED);

    if (completedChunks >= file.getTotalChunks() && file.getPlaybackStatus() != PlaybackStatusEnum.PENDING) {
      log.info("[PipelineEventsService] All chunks for fileId {} are completed. Marking pipeline as COMPLETED.",
          fileId);

      file.setFileStatus(FileStatusEnum.COMPLETED);
      fileMetadataRepository.save(file);

      PipelineEventDTO completedEventDTO = PipelineEventDTO.builder()
          .fileId(fileId)
          .eventType(PipelineEventEnum.PIPELINE_COMPLETE)
          .message("Pipeline completed successfully")
          .build();

      String metaData = completedEventDTO.getMetadata() != null
          ? objectMapper.writeValueAsString(completedEventDTO.getMetadata())
          : null;

      PipelineEvent entity = PipelineEvent.builder()
          .fileId(fileId)
          .eventType(PipelineEventEnum.PIPELINE_COMPLETE)
          .message("Pipeline completed successfully")
          .metadata(metaData)
          .build();
      pipelineEventRepository.save(entity);

      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        public void afterCommit() {
          sseEmitterRegistry.broadcast(completedEventDTO);
          sseEmitterRegistry.completeEmitters(fileId);
        }
      });
    }
  }

}
