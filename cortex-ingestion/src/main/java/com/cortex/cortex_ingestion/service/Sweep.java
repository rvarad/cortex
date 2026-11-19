package com.cortex.cortex_ingestion.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The sweep (failed-states): ingestion's scheduled job. It deletes uploads that never landed, and
 * rule 1 fails a file that has stopped making progress, judged by its silence, not its age.
 */
@Slf4j
@Component
public class Sweep {

  private final PipelineEventRepository pipelineEventRepository;
  private final PipelineEventsService pipelineEventsService;
  private final FileMetadataRepository fileMetadataRepository;
  private final GcsStorageService gcsStorageService;

  /** UPLOADED files: covers the media queue wait (failed-states, Decisions, "N is a guess"). */
  private final Duration uploadedSilence;

  /** CHUNKED and PROCESSING files: covers a silent transcode. */
  private final Duration processingSilence;

  /** PENDING files: the signed URL's 30 minutes, plus an upload that started before it expired. */
  private final Duration abandonedUploadAge;

  public Sweep(PipelineEventRepository pipelineEventRepository, PipelineEventsService pipelineEventsService,
      FileMetadataRepository fileMetadataRepository, GcsStorageService gcsStorageService,
      @Value("${cortex.sweep.uploaded-silence}") Duration uploadedSilence,
      @Value("${cortex.sweep.processing-silence}") Duration processingSilence,
      @Value("${cortex.sweep.abandoned-upload-age}") Duration abandonedUploadAge) {
    this.pipelineEventRepository = pipelineEventRepository;
    this.pipelineEventsService = pipelineEventsService;
    this.fileMetadataRepository = fileMetadataRepository;
    this.gcsStorageService = gcsStorageService;
    this.uploadedSilence = uploadedSilence;
    this.processingSilence = processingSilence;
    this.abandonedUploadAge = abandonedUploadAge;
  }

  // The first run waits the shorter silence, so events that waited in Kafka while ingestion was down
  // are stored before anything is judged; an UPLOADED file needs the longer one anyway.
  @Scheduled(initialDelayString = "${cortex.sweep.processing-silence}", fixedDelayString = "${cortex.sweep.interval}")
  public void run() {
    LocalDateTime now = LocalDateTime.now(); // the JVM's clock wrote both created_at columns
    deleteAbandonedUploads(now.minus(abandonedUploadAge));
    // Each status has its own silence: an UPLOADED file is picked after 2 hours, the others after 45 minutes.
    for (UUID fileId : pipelineEventRepository.findSilentFiles(now.minus(uploadedSilence), now.minus(processingSilence))) {
      MDC.put("fileId", fileId.toString());
      try {
        pipelineEventsService.failIfStuck(fileId); // through the proxy: one transaction per file
      } catch (RuntimeException e) { // The boundary for this file: logged once; the next file is still judged.
        log.error("Sweep could not judge a silent file", e);
      } finally {
        MDC.remove("fileId");
      }
    }
    pipelineEventsService.endOpenStreamsOfEndedFiles();
  }

  /**
   * A PENDING row whose upload never landed: deleted, not failed. The pipeline never began, so
   * there is nothing to keep (failed-states, brick 11b).
   */
  private void deleteAbandonedUploads(LocalDateTime createdBefore) {
    for (FileMetadata file : fileMetadataRepository.findByFileStatusAndCreatedAtBefore(FileStatusEnum.PENDING,
        createdBefore)) {
      MDC.put("fileId", file.getId().toString());
      try {
        // The row first: the conditional delete decides. Deleting the object first could remove an
        // upload whose webhook marks it UPLOADED at the same moment.
        if (fileMetadataRepository.deleteIfPending(file.getId()) == 1) {
          // Usually nothing there; removes an upload that landed but whose webhook never came.
          gcsStorageService.deleteObject(file.getObjectName(), file.getId());
          log.info("Upload never finished: its row was deleted");
        }
      } catch (RuntimeException e) { // The boundary for this file: logged once; the next is still handled.
        log.error("Sweep could not delete an abandoned upload", e);
      } finally {
        MDC.remove("fileId");
      }
    }
  }
}
