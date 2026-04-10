package com.cortex.cortex_ingestion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KafkaProducerService {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @Value("${app.kafka.topic.file-ingested.media}")
  private String mediaTopic;

  @Value("${app.kafka.topic.file-ingested.document}")
  private String documentTopic;

  @Value("${app.kafka.topic.pipeline-events}")
  private String pipelineEventsTopic;

  public void sendFileIngestedEvent(FileIngestionEventDTO event) {
    String topic = determineTopic(event.getContentType());

    kafkaTemplate.send(topic, event.getFileId().toString(), event);
  }

  public void sendPipelineEvent(PipelineEventDTO event) {
    kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(), event);
  }

  private String determineTopic(String contentType) {
    if (contentType.startsWith("video/") || contentType.startsWith("audio/")) {
      return mediaTopic;
      // } else if (contentType.equals("application/pdf") ||
      // contentType.startsWith("word")) {
    } else {
      return documentTopic;
    }
  }
}