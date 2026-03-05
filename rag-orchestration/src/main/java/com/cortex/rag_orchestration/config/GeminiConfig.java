package com.cortex.rag_orchestration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;

@Configuration
public class GeminiConfig {

  @Value("${spring.cloud.gcp.project-id}")
  private String projectId;

  @Value("${spring.cloud.gcp.location}")
  private String location;

  @Bean
  public Client genAiClient() {
    // Vertex AI requires a Region (e.g. "asia-south1"), but GCP configuration often
    // provides a Zone (e.g. "asia-south1-c").
    // We can safely convert a zone to a region by stripping the trailing "-[a-z]"
    // if it's present.
    String region = location.replaceAll("-[a-z]$", "");

    return Client.builder()
        .vertexAI(true)
        .project(projectId)
        .location(region)
        .build();
  }
}
