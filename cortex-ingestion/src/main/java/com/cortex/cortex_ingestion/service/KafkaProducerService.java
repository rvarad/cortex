package com.cortex.cortex_ingestion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.kafka.KafkaSends;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    KafkaSends.await(kafkaTemplate.send(topic, event.getFileId().toString(), event));
  }

  /** A glass box event: it only feeds the timeline, so a failed send is a WARN (ADR-0014, 2026-10-30). */
  public void sendGlassBoxEvent(PipelineEventDTO event) {
    try {
      KafkaSends.await(kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(), event));
    } catch (RuntimeException e) { // Not rethrown: the timeline loses one event; the file's work must go on.
      log.warn("Glass box event {} not sent", event.getEventType(), e);
    }
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