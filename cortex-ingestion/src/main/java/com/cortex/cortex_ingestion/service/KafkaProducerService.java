package com.cortex.cortex_ingestion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;

@Service
public class KafkaProducerService {

  private final KafkaTemplate<String, FileIngestionEventDTO> kafkaTemplate;

  @Value("${app.kafka.topic.file-ingested.media}")
  private String mediaTopic;

  @Value("${app.kafka.topic.file-ingested.document}")
  private String documentTopic;

  public KafkaProducerService(KafkaTemplate<String, FileIngestionEventDTO> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void sendFileIngestedEvent(FileIngestionEventDTO event) {
    String topic = determineTopic(event.getContentType());

    kafkaTemplate.send(topic, event.getFileId().toString(), event);
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