package com.cortex.cortex_ingestion.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.cortex.cortex_common.model.UploadRejectReasonEnum;
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

  /** The events the page ends a run on (page.tsx, TERMINAL_EVENTS). */
  private static final Set<PipelineEventEnum> TERMINAL_EVENTS = Set.of(
      PipelineEventEnum.PIPELINE_COMPLETE, PipelineEventEnum.PIPELINE_FAILED, PipelineEventEnum.UPLOAD_REJECTED);

  public SseEmitter subscribeToEvents(UUID fileId, String userId) throws IOException {
    log.info("[PipelineEventsService] Client subscribed to events for fileId: {}", fileId);

    FileMetadata file = fileMetadataRepository.findByIdAndUserId(fileId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    SseEmitter emitter = new SseEmitter(0L);

    List<PipelineEvent> history = sendPreviousEvents(fileId, emitter);

    if (file.getFileStatus().isTerminal()) {
      log.info("[PipelineEventsService] File is {}: its run has ended. Closing emitter for fileId: {}",
          file.getFileStatus(), fileId);
      // Its terminal event is a glass box send and can be lost, while the status was written:
      // the page then learns the outcome from the row. Not stored (failed-states, brick 8).
      PipelineEventDTO end = history.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.getEventType()))
          ? null
          : endOfRun(file);
      if (end != null) {
        emitter.send(SseEmitter.event().name(end.getEventType().name()).data(end));
      }
      emitter.complete();
    } else {
      sseEmitterRegistry.addEmitter(fileId, emitter);
    }

    return emitter;
  }

  /**
   * Sent before the endpoint returns, so Spring buffers them: a client can't have left yet. What
   * can fail is stored metadata that won't read, and that is a 500 at the boundary.
   */
  private List<PipelineEvent> sendPreviousEvents(UUID fileId, SseEmitter emitter) throws IOException {
    List<PipelineEvent> historicalEvents = pipelineEventRepository.findByFileIdOrderByCreatedAtAsc(fileId);

    for (PipelineEvent event : historicalEvents) {
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
    }
    return historicalEvents;
  }

  /** The terminal event a file's status implies, or null when there is none to make up. */
  private static PipelineEventDTO endOfRun(FileMetadata file) {
    return switch (file.getFileStatus()) {
      // An error: rule 1 stores its own PIPELINE_FAILED with the status, so it is never missing.
      case FOR_CLEANUP, FAILED -> PipelineEventDTO.builder().fileId(file.getId())
          .eventType(PipelineEventEnum.PIPELINE_FAILED).message(PipelineEventEnum.FAILED_BY_ERROR_MESSAGE).build();
      case REJECTED -> PipelineEventDTO.builder().fileId(file.getId())
          .eventType(PipelineEventEnum.UPLOAD_REJECTED).message(rejectedMessage(file.getRejectionReason())).build();
      default -> null; // PIPELINE_COMPLETE is stored with COMPLETED, in one transaction
    };
  }

  /** No limits in the text: they are config in two services. */
  private static String rejectedMessage(UploadRejectReasonEnum reason) {
    if (reason == null) {
      return "This file cannot be processed.";
    }
    return switch (reason) {
      case TOO_BIG -> "This file is too large to be processed.";
      case TOO_LONG -> "This file is too long to be processed.";
      case CORRUPTED -> "This file appears to be corrupted and cannot be processed.";
    };
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
      // Re-thrown unchecked: @Transactional rolls back only on those, and the Kafka handler reports it.
      throw new IllegalStateException("Could not process pipeline event for fileId " + eventDTO.getFileId(), e);
    }
  }

  /**
   * Rule 1 of the sweep, for one silent file: completes it if all its work is saved, else fails it
   * (failed-states, Decisions). One transaction: the row lock, the status and the stored event.
   */
  @Transactional
  public void failIfStuck(UUID fileId) {
    try {
      if (checkPipelineCompletion(fileId)) {
        return; // its work was all saved, only the last trigger was lost: completed, not failed
      }
    } catch (JsonProcessingException e) { // Unchecked, as in processNewEvent: the transaction rolls back.
      throw new IllegalStateException("Could not complete fileId " + fileId, e);
    }
    if (fileMetadataRepository.markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES) == 0) {
      return; // its run ended, or it was deleted, since the sweep's query
    }
    // WARN, not ERROR: no exception, nothing of ours failed in a way a stack trace explains.
    log.warn("[PipelineEventsService] No activity for too long: marked failed");
    // Stored in this transaction, with the status: so a failed file with no PIPELINE_FAILED was
    // failed by an error (brick 8). processNewEvent broadcasts it to open pages after the commit.
    processNewEvent(PipelineEventDTO.builder().fileId(fileId)
        .eventType(PipelineEventEnum.PIPELINE_FAILED)
        .message(PipelineEventEnum.FAILED_BY_NO_PROGRESS_MESSAGE)
        .build());
  }

  /**
   * Ends the open pages of files whose run has ended but whose terminal event they never got (its
   * glass box send was lost): sends brick 8's event, built from the row, and closes the stream.
   */
  public void endOpenStreamsOfEndedFiles() {
    for (UUID fileId : sseEmitterRegistry.openFileIds()) {
      FileMetadata file = fileMetadataRepository.findById(fileId).orElse(null);
      PipelineEventDTO end = file == null ? null : endOfRun(file); // null while it still runs
      if (end != null) {
        sseEmitterRegistry.broadcast(end);
        sseEmitterRegistry.completeEmitters(fileId);
      }
    }
  }

  /** True when this call completed the file. */
  private boolean checkPipelineCompletion(UUID fileId) throws JsonProcessingException {

    FileMetadata file = fileMetadataRepository.findByIdForUpdate(fileId).orElse(null);

    if (file == null) {
      log.warn("[PipelineEventsService] File Metadata not found for fileId: {}", fileId);
      return false;
    }

    // Under the row lock: a file that failed or was rejected while this event waited stays so.
    if (file.getFileStatus().isTerminal())
      return false;

    if (file.getTotalChunks() == null || file.getTotalChunks() == 0)
      return false;

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
      return true;
    }
    return false;
  }

}
