package com.cortex.cortex_media_processing_service.service;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VisionService {

  private final RestClient restClient;

  @Value("${gemini.api-key}")
  private String apiKey;

  public VisionService(RestClient.Builder builder) {
    this.restClient = builder.build();
  }

  public String describe(byte[] imageBytes) {
    try {
      String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
          + apiKey;

      String base64Image = Base64.getEncoder().encodeToString(imageBytes);

      var body = Map.of(
          "contents", List.of(Map.of(
              "parts", List.of(
                  Map.of("text", "Describe this image in detail."),
                  Map.of("inline_data", Map.of(
                      "mime_type", "image/jpeg",
                      "data", base64Image))))));

      Map response = restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
          .body(Map.class);

      return extractTextFromResponse(response);

    } catch (Exception e) {
      log.error("Failed to describe image: ", e);
      return "";
    }
  }

  private String extractTextFromResponse(Map response) {
    try {
      List<Map> candidates = (List<Map>) response.get("candidates");

      if (candidates == null || candidates.isEmpty())
        return "";

      Map content = (Map) candidates.get(0).get("content");
      List<Map> parts = (List<Map>) content.get("parts");
      return (String) parts.get(0).get("text");
    } catch (Exception e) {
      log.error("Failed to extract text from response: ", e);
      return "";
    }
  }
}