package com.cortex.rag_orchestration.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.RateLimiter;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

  @Value("${gcs.bucket}")
  private String bucketName;

  private final Client genAiClient;

  private final GcsStorageService gcsStorageService;

  private final RateLimiter inferenceRateLimiter = RateLimiter.create(15 / 60.0);

  private static final String MODEL_NAME = "gemini-2.5-flash";

  private static final String VIDEO_ANALYSIS_PROMPT = """
      You are an expert video archivist building a searchable retrieval system.
      Analyze this video segment and provide a dense, factual description of what
      is happening.

      Instructions for Multimodal Analysis:
      You must listen to the audio track to understand the context, accurately
      identify ambiguous objects, and gauge the emotional tone of the scene.
      However, do NOT transcribe the spoken dialogue. Another dedicated system is
      capturing the transcript.

      Focus your output strictly on describing:

      Context & Setting: Where are they, and what is the overarching situation
      (informed by the audio)?

      Subjects: Who is in the frame? (Describe clothing, emotions, and
      interactions).

      Actions & Objects: What exact movements are taking place? Use the audio to
      specifically name the tools, objects, or concepts being shown visually.

      On-Screen Text: Transcribe any visible text, signs, or overlays.

      Provide a single, highly descriptive paragraph that captures the full
      semantic reality of the scene without repeating the spoken words.
      """;

  public String generateDescription(String videoPath) {

    try {
      String gcsUri = String.format("gs://%s/%s", bucketName, videoPath);

      log.info("Generating description for video: {}", gcsUri);

      // inferenceRateLimiter.acquire();

      GenerateContentResponse response = genAiClient.models.generateContent(MODEL_NAME,
          Content.fromParts(Part.fromUri(gcsUri, "video/mp4"), Part.fromText(VIDEO_ANALYSIS_PROMPT)), null);

      return response.text();
    } catch (Exception e) {
      log.error("Error generating description for video: {}", videoPath, e);
      throw new RuntimeException("Error generating description for video: " + videoPath, e);
    }
  }
}
