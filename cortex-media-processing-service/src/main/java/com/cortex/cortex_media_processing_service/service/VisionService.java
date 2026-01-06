package com.cortex.cortex_media_processing_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
// @Service
public class VisionService {

  private final RestClient restClient;

  @Value("${gemini.api-key}")
  private String apiKey;

  public VisionService(RestClient.Builder builder) {
    this.restClient = builder.build();
  }
}