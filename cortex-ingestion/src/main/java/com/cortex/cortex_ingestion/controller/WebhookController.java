package com.cortex.cortex_ingestion.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.service.MinioStorageService;

@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

  private final MinioStorageService minioStorageService;

  public WebhookController(MinioStorageService minioStorageService) {
    this.minioStorageService = minioStorageService;
  }

  @PostMapping("/minio")
  public ResponseEntity<String> handleMinioEvent(@RequestBody Map<String, Object> eventPayload) {
    System.out.println("Received Minio event: " + eventPayload);

    if (eventPayload.containsKey("Records")) {
      List<Map<String, Object>> records = (List<Map<String, Object>>) eventPayload.get("Records");

      for (Map<String, Object> record : records) {
        Map<String, Object> s3 = (Map<String, Object>) record.get("s3");
        Map<String, Object> object = (Map<String, Object>) s3.get("object");
        String objectName = (String) object.get("key");

        minioStorageService.handleFileUploadNotification(objectName);
      }
    }
    return ResponseEntity.ok("Minio event received successfully");
  }

  @PostMapping("/upload")
  public ResponseEntity<Void> handleFileUploadNotification(@RequestHeader("ce-subject") String objectName,
      @RequestHeader("ce-source") String source, @RequestHeader("ce-type") String type,
      @RequestBody Map<String, Object> eventPayload) {

    System.out.println("Received file upload notification: " + objectName);
    System.out.println("Received file upload notification: " + source);
    System.out.println("Received file upload notification: " + type);
    System.out.println("Received file upload notification: " + eventPayload);

    return ResponseEntity.ok().build();
  }
}
