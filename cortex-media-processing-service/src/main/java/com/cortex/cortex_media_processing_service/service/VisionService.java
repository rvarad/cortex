package com.cortex.cortex_media_processing_service.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.RateLimiter;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.google.genai.types.UploadFileConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class VisionService {

  private final Client genAiClient;
  private final MinioStorageService minioStorageService;

  private final RateLimiter filesApiUploadRateLimiter = RateLimiter.create(1);
  private final RateLimiter inferenceRateLimiter = RateLimiter.create(15 / 60.0);

  private static final String VIDEO_ANALYSIS_PROMPT = """
      You are an expert video archivist building a searchable retrieval system. Analyze this video segment and provide a dense, factual description of what is happening.

      Instructions for Multimodal Analysis:
      You must listen to the audio track to understand the context, accurately identify ambiguous objects, and gauge the emotional tone of the scene. However, do NOT transcribe the spoken dialogue. Another dedicated system is capturing the transcript.

      Focus your output strictly on describing:

      Context & Setting: Where are they, and what is the overarching situation (informed by the audio)?

      Subjects: Who is in the frame? (Describe clothing, emotions, and interactions).

      Actions & Objects: What exact movements are taking place? Use the audio to specifically name the tools, objects, or concepts being shown visually.

      On-Screen Text: Transcribe any visible text, signs, or overlays.

      Provide a single, highly descriptive paragraph that captures the full semantic reality of the scene without repeating the spoken words.
      """;

  private File uploadFile(String objectName) {
    Path tempFilePath = Path.of("/tmp/gemini-file-uploads", objectName);
    try {
      filesApiUploadRateLimiter.acquire();

      Files.createDirectories(tempFilePath.getParent());
      minioStorageService.downloadFile(objectName, tempFilePath);

      int maxRetries = 3;
      int retryDelayMs = 1000;
      for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
          log.info("Uploading file: {} to gemini using files api, attempt: {}/{}", objectName, attempt, maxRetries);

          File uploadedFile = genAiClient.files.upload(tempFilePath.toFile(),
              UploadFileConfig.builder().mimeType("video/mp4").build());

          log.info("File uploaded successfully: {}", objectName);
          log.info("File from gemini files api: {}", uploadedFile);

          return uploadedFile;

        } catch (Exception e) {
          log.warn("Upload attempt {}/{} failed for {}, Error: {}", attempt, maxRetries, objectName, e.getMessage());

          if (attempt == maxRetries)
            throw e;

          Thread.sleep(attempt * retryDelayMs);
        }
      }
      throw new RuntimeException("Upload retries exhausted for: " + objectName);

    } catch (Exception e) {
      throw new RuntimeException("Failed to upload file to gemini using files api: " + objectName + "due to: " + e);
    } finally {
      try {
        Files.deleteIfExists(tempFilePath);
      } catch (Exception e) {
        log.warn("Failed to delete temp file: {}", tempFilePath, e);
      }
    }
  }

  private File waitForFileToBeActivated(File uploadedFile, String objectName) {
    try {
      File currentFile = uploadedFile;
      int attempts = 0;

      while (!currentFile.state().isPresent() ||
          !"ACTIVE".equals(currentFile.state().get().toString())) {
        if (attempts++ > 30) {
          throw new RuntimeException("Timed out waiting for file to process: " + objectName);
        }
        if (currentFile.state().isPresent() && "FAILED".equals(currentFile.state().get().toString())) {
          throw new RuntimeException("File failed to process: " + objectName);
        }
        log.info("Waiting for file to be active, current state: {} for object: {}", currentFile.state(), objectName);
        Thread.sleep(2000);
        currentFile = genAiClient.files.get(currentFile.name().get(), null);
      }

      log.info("File processed by gemini, ready for inference: {}", objectName);
      return currentFile;
    } catch (Exception e) {
      throw new RuntimeException("Error waiting for file to process", e);
    }
  }

  public String describeVideoChunk(String objectName) {
    try {
      File newFile = uploadFile(objectName);
      File activeFile = waitForFileToBeActivated(newFile, objectName);

      inferenceRateLimiter.acquire();
      log.info("Inference started for objectName: {}", objectName);

      GenerateContentResponse response = genAiClient.models.generateContent("gemini-3-flash-preview",
          Content.fromParts(Part.fromText(VIDEO_ANALYSIS_PROMPT),
              Part.fromUri(activeFile.uri().get(), activeFile.mimeType().get())),
          null);

      genAiClient.files.delete(activeFile.name().get(), null);

      return response.text();
    } catch (Exception e) {
      throw new RuntimeException("Error describing video chunk: " + objectName, e);
    }
  }

}