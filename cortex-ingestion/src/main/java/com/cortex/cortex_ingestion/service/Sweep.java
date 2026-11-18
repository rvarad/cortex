package com.cortex.cortex_ingestion.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The sweep (failed-states): ingestion's scheduled job. Rule 1 fails a file that has stopped making
 * progress, judged by its silence, not its age.
 */
@Slf4j
@Component
public class Sweep {

  private final PipelineEventRepository pipelineEventRepository;
  private final PipelineEventsService pipelineEventsService;

  /** UPLOADED files: covers the media queue wait (failed-states, Decisions, "N is a guess"). */
  private final Duration uploadedSilence;

  /** CHUNKED and PROCESSING files: covers a silent transcode. */
  private final Duration processingSilence;

  public Sweep(PipelineEventRepository pipelineEventRepository, PipelineEventsService pipelineEventsService,
      @Value("${cortex.sweep.uploaded-silence}") Duration uploadedSilence,
      @Value("${cortex.sweep.processing-silence}") Duration processingSilence) {
    this.pipelineEventRepository = pipelineEventRepository;
    this.pipelineEventsService = pipelineEventsService;
    this.uploadedSilence = uploadedSilence;
    this.processingSilence = processingSilence;
  }

  // The first run waits the shorter silence, so events that waited in Kafka while ingestion was down
  // are stored before anything is judged; an UPLOADED file needs the longer one anyway.
  @Scheduled(initialDelayString = "${cortex.sweep.processing-silence}", fixedDelayString = "${cortex.sweep.interval}")
  public void run() {
    LocalDateTime now = LocalDateTime.now(); // the JVM's clock wrote both created_at columns
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
}
