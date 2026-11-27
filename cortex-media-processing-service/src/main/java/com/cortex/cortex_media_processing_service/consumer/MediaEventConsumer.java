package com.cortex.cortex_media_processing_service.consumer;

import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_media_processing_service.service.MediaProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEventConsumer {

  private final ThreadPoolTaskExecutor mediaProcessingExecutor;

  private final MediaProcessingService mediaProcessingService;

  @KafkaListener(topics = "${app.kafka.topic.media}", groupId = "cortex-media-processing-group")
  public void consume(FileIngestionEventDTO event) throws InterruptedException, Exception {
    MDC.put("fileId", event.getFileId().toString());
    try {
      log.info("Received file ingestion event for fileId: {}", event.getFileId());
      // Handed off, never waited on: a job runs 15-40 minutes, far past max.poll.interval.ms, and
      // a listener that blocks that long gets evicted and the record redelivered (ADR-0014). The
      // job's failures end at its own boundary, BackgroundTask, not here.
      mediaProcessingExecutor.execute(BackgroundTask.of("media job", () -> mediaProcessingService.processMedia(
          event.getObjectName(),
          event.getFileId(),
          event.getUserId())));
    } finally {
      MDC.remove("fileId");
    }
    // .getThreadPoolExecutor()
    // .getQueue()
    // .put(() -> mediaProcessingService.processMedia(
    // event.getObjectName(),
    // event.getFileId(),
    // event.getUserId()));
  }
}
