package com.cortex.cortex_ingestion.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cortex.cortex_ingestion.service.GcsStorageService;

import lombok.extern.slf4j.Slf4j;

/**
 * The upload webhook, called by a Pub/Sub push subscription. Its status code is an instruction to
 * Pub/Sub (ADR-0014): a 2xx means "done", anything else means "send it again", for days. A
 * notification that can never be processed is answered 200 and logged here, once. A failure that
 * may pass (GCS, the database, Kafka) goes to ApiExceptionHandler and is a 500.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
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

    log.info("Received notification: subject={}, source={}, type={}, payload={}", objectName, source, type,
        eventPayload);

    // 1. Support CloudEvents (ce-subject header)
    String finalObjectName = objectName;

    // 2. Support Pub/Sub Push Envelope (message.attributes.objectId)
    if (finalObjectName == null || finalObjectName.isEmpty()) {
      if (eventPayload != null && eventPayload.containsKey("message")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) eventPayload.get("message");
        if (message != null && message.containsKey("attributes")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> attributes = (Map<String, Object>) message.get("attributes");
          if (attributes != null && attributes.containsKey("objectId")) {
            finalObjectName = attributes.get("objectId").toString();
          }
        }
      }
    }

    // 3. Fallback: Direct JSON body (just in case)
    if (finalObjectName == null || finalObjectName.isEmpty()) {
      if (eventPayload != null && eventPayload.containsKey("name")) {
        finalObjectName = eventPayload.get("name").toString();
      }
    }

    if (finalObjectName != null && !finalObjectName.isEmpty()) {
      // The objectName from GCS looks like "uploads/media/uuid.mp4"
      String normalizedObjectName = finalObjectName;
      if (normalizedObjectName.startsWith("objects/")) {
        normalizedObjectName = normalizedObjectName.substring(8);
      }

      // BREAK THE INFINITE LOOP: Only process initial media uploads
      if (!normalizedObjectName.startsWith("uploads/media/")) {
        log.info("Ignoring event for non-media path: {}", normalizedObjectName);
        return ResponseEntity.ok().build();
      }

      log.info("Processing file: {}", normalizedObjectName);
      gcsStorageService.handleFileUploadSuccess(normalizedObjectName);
      return ResponseEntity.ok().build();
    }

    log.error("Notification dropped, it has no object name. ce-subject={}, payload={}", objectName, eventPayload);
    return ResponseEntity.ok().build();
  }

  /**
   * Sending these again would fail the same way every time, so the answer is "done" and this line
   * is how we hear of it. A ResponseStatusException from below means the object can never be
   * processed: its 4xx is never sent, because to Pub/Sub a 4xx means "send it again".
   */
  @ExceptionHandler({ ResponseStatusException.class, HttpMessageNotReadableException.class })
  public ResponseEntity<Void> dropNotification(Exception ex) {
    String reason = ex instanceof ResponseStatusException statusException ? statusException.getReason() : ex.getMessage();
    log.error("Notification dropped, it can never be processed: {}", reason);
    return ResponseEntity.ok().build();
  }
}
