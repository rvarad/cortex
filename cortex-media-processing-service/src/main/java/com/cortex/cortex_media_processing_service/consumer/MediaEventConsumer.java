package com.cortex.cortex_media_processing_service.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_media_processing_service.service.MediaProcessingService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MediaEventConsumer {

  private final MediaProcessingService mediaProcessingService;

  @KafkaListener(topics = "${app.kafka.topic.media}", groupId = "cortex-media-processing-group")
  public void consume(FileIngestionEventDTO event) {
    mediaProcessingService.processMedia(event.getObjectName(), event.getFileId());
  }
}
