package com.cortex.cortex_ingestion.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

  @PostMapping("/minio")
  public ResponseEntity<String> handleMinioEvent(@RequestBody Map<String, Object> eventPayload) {
    System.out.println("Received Minio event: " + eventPayload);
    return ResponseEntity.ok("Minio event received successfully");
  }
}
