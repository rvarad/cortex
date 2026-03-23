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

    // Extract size safely
    long size = 0;
    if (eventPayload != null && eventPayload.containsKey("size")) {
      try {
        size = Long.parseLong(eventPayload.get("size").toString());
      } catch (NumberFormatException e) {
        log.warn("Failed to parse size from payload: {}", eventPayload.get("size"));
      }
    }
    log.info(" - Size: {} bytes", size);
    log.info(" - Payload: " + eventPayload);

    if (objectName != null && !objectName.isEmpty()) {
      // The objectName from GCS looks like "objects/uploads/media/uuid.mp4"
      String normalizedObjectName = objectName.startsWith("objects/") ? objectName.substring(8) : objectName;

      // BREAK THE INFINITE LOOP: Only process initial media uploads
      if (!normalizedObjectName.startsWith("uploads/media/")) {
        log.info("Ignoring event for non-media path: " + normalizedObjectName);
        return ResponseEntity.ok().build();
      }

      log.info("Processing file: " + normalizedObjectName);
      gcsStorageService.handleFileUploadSuccess(normalizedObjectName, size);
      return ResponseEntity.ok().build();
    }

    log.error("CloudEvent missing subject (object name). Payload: " + eventPayload);
    return ResponseEntity.badRequest().build();
  }
}
