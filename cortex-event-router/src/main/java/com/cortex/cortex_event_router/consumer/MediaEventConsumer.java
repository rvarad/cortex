package com.cortex.cortex_event_router.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MediaEventConsumer {

  @KafkaListener(topics = "${app.kafka.topic.file-ingested.media}", groupId = "cortex-event-router-group")
  public void onMediaIngested(FileIngestionEventDTO event) {
    log.info("Event Router: Received media event: {}", event);
    log.info("Event Router: Received media event for file: {}",
        event.getFileId());
    log.info("Object Name: {}", event.getObjectName());
    log.info("Content Type: {}", event.getContentType());

    // Logic for routing to other services will go here
    log.info("Successfully processed media event for file ID: {}",
        event.getFileId());
  }
}
