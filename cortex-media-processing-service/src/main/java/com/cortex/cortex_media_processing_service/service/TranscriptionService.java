package com.cortex.cortex_media_processing_service.service;

import java.io.InputStream;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

  private final OpenAiAudioTranscriptionModel transcriptionModel;
  private final MinioStorageService minioStorageService;

  private final RateLimiter transcriptionRateLimiter = RateLimiter.create(20.0 / 60.0);

  public String transcribe(String objectName) {
    try {
      transcriptionRateLimiter.acquire();

      int maxRetries = 3;
      int retryDelayMs = 1000;

      for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try (InputStream audioStream = minioStorageService.getFileStream(objectName)) {
          log.info("Transcribing file: {}, attempt: {}/{}", objectName, attempt, maxRetries);

          String filename = objectName.substring(objectName.lastIndexOf("/") + 1);

          InputStreamResource resource = new InputStreamResource(audioStream) {
            @Override
            public String getFilename() {
              return filename;
            }

            @Override
            public long contentLength() {
              return minioStorageService.getFileSize(objectName);
            }
          };

          AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(resource);
          AudioTranscriptionResponse response = transcriptionModel.call(prompt);

          String text = response.getResult().getOutput();
          log.info("Transcription completed for: {}", objectName);

          return text;

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