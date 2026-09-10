package com.cortex.cortex_rag_orchestration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.google.genai.Client;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class GeminiConfig {

  // @Value("${spring.cloud.gcp.project-id}")
  // private String projectId;

  // @Value("${spring.cloud.gcp.location}")
  // private String location;

  @Bean
  public Client genAiClient(Environment env) {
    // Vertex AI requires a Region (e.g. "asia-south1"), but GCP configuration often
    // provides a Zone (e.g. "asia-south1-c").
    // We can safely convert a zone to a region by stripping the trailing "-[a-z]"
    // if it's present.
    // Config is read from Environment rather than @Value on purpose. A Spring AI
    // condition (CachedContentServiceCondition) calls getBean() during the
    // post-processor phase, which builds this bean before @Value placeholders are
    // resolved. Environment is populated before any bean exists, so it can't be
    // caught out by bean ordering.
    String projectId = env.getProperty("spring.cloud.gcp.project-id");
    String location = env.getProperty("spring.cloud.gcp.location");

    if (projectId == null || location == null) {
      throw new IllegalStateException(
          "spring.cloud.gcp.project-id / spring.cloud.gcp.location not set. Active profile?");
    }

    String region = location.replaceAll("-[a-z]$", "");
    log.info("[GeminiConfig] Initializing Client for Vertex AI.Project: {}, Location: {}", projectId, region);

    return Client.builder()
        .vertexAI(true)
        .project(projectId)
        .location(region)
        .build();
  }
}
