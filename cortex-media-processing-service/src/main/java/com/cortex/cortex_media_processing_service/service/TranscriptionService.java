package com.cortex.cortex_media_processing_service.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TranscriptionService {

  private final RestClient restClient;

  @Value("${openai.api-key}")
  private String apiKey;

  @Value("${openai.base-url}")
  private String baseUrl;

  @Value("${openai.model}")
  private String model;

  public TranscriptionService(RestClient.Builder builder) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  public Map<String, Object> transcribe(byte[] wavData) {
    try {
      MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
      body.add("file", new ByteArrayResource(wavData) {
        public String getFilename() {
          return "audio.wav";
        }
      });
      body.add("model", model);
      body.add("response_format", "verbose_json");

      return restClient.post().uri(baseUrl).header("Authorization", "Bearer " + apiKey)
          .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(Map.class);

    } catch (Exception e) {
      log.error("Transcription failed: ", e);
      return Map.of();
    }
  }
}