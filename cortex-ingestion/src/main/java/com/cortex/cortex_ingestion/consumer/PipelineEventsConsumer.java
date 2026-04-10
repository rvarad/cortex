package com.cortex.cortex_ingestion.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_ingestion.service.PipelineEventsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventsConsumer {

  private final PipelineEventsService pipelineEventsService;

  @KafkaListener(topics = "${app.kafka.topic.pipeline-events}", groupId = "cortex-ingestion-group")
  public void consumePipelineEvents(PipelineEventDTO eventDTO) {
    log.info("Received pipeline event for fileId {} : {}", eventDTO.getFileId(), eventDTO.getEventType());

    pipelineEventsService.processNewEvent(eventDTO);
  }

}
