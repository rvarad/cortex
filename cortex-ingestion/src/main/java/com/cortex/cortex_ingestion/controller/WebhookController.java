package com.cortex.cortex_ingestion.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.service.GcsStorageService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

  private final GcsStorageService gcsStorageService;

  public WebhookController(GcsStorageService gcsStorageService) {
    this.gcsStorageService = gcsStorageService;
  }

  @PostMapping("/notify-upload")
  public ResponseEntity<Void> handleFileUploadNotification(
      @RequestHeader(value = "ce-subject", required = false) String objectName,
      @RequestHeader(value = "ce-source", required = false) String source,
      @RequestHeader(value = "ce-type", required = false) String type,
      @RequestBody Map<String, Object> eventPayload) {

    log.info("Received CloudEvent notification:");
    log.info(" - Subject (Object): " + objectName);
    log.info(" - Source: " + source);
    log.info(" - Type: " + type);
    log.info(" - Payload: " + eventPayload);

    if (objectName != null && !objectName.isEmpty()) {
      // The objectName from GCS looks like "objects/quarantine/uuid.mp4"
      // We extract only the last part (the filename) to match our database ID.
      String fileNameOnly = objectName.substring(objectName.lastIndexOf('/') + 1);

      log.info("Processing file ID: " + fileNameOnly);
      gcsStorageService.handleFileUploadSuccess(fileNameOnly);
      return ResponseEntity.ok().build();
    }

    log.error("CloudEvent missing subject (object name). Payload: " + eventPayload);
    return ResponseEntity.badRequest().build();
  }
}
