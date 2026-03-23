package com.cortex.cortex_rag_orchestration.service;

import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.google.common.util.concurrent.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

  private final RestClient audioRestClient;
  private final ObjectMapper objectMapper;

  @Value("${spring.ai.openai.audio.transcription.options.model}")
  private String model;

  private final GcsStorageService gcsStorageService;

  private final RateLimiter transcriptionRateLimiter = RateLimiter.create(20.0 / 60.0);

  public record TranscriptionResult(String transcript, String languageCode) {
  }

  public TranscriptionResult transcribe(String objectName) {
    try {
      transcriptionRateLimiter.acquire();

      int maxRetries = 3;
      int retryDelayMs = 1000;

      for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try (InputStream audioStream = gcsStorageService.getFileStream(objectName)) {
          log.info("Transcribing file: {}, attempt: {}/{}", objectName, attempt, maxRetries);

          String filename = objectName.substring(objectName.lastIndexOf("/") + 1);

          InputStreamResource resource = new InputStreamResource(audioStream) {
            @Override
            public String getFilename() {
              return filename;
            }

            @Override
            public long contentLength() {
              return gcsStorageService.getFileSize(objectName);
            }
          };

          MultipartBodyBuilder builder = new MultipartBodyBuilder();
          builder.part("file", resource, MediaType.parseMediaType("audio/wav"));
          builder.part("model", model);
          builder.part("response_format", "verbose_json");

          String jsonOutput = audioRestClient.post()
              .uri("/v1/audio/transcriptions")
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(builder.build())
              .retrieve()
              .body(String.class);

          JsonNode rootNode = objectMapper.readTree(jsonOutput);
          String text = rootNode.path("text").asText();
          String language = rootNode.path("language").asText();

          log.info("Transcription completed for: {}", objectName);

          return new TranscriptionResult(text, language);

        } catch (Exception e) {
          log.warn("Transcription attempt {}/{} failed for {}, Error: {}", attempt, maxRetries, objectName,
              e.getMessage());

          if (attempt == maxRetries)
            throw e;

          Thread.sleep(attempt * retryDelayMs);
        }
      }

      throw new RuntimeException("Transcription retries exhausted for: " + objectName);

    } catch (Exception e) {
      throw new RuntimeException("Failed to transcribe file: " + objectName, e);
    }
  }
}
