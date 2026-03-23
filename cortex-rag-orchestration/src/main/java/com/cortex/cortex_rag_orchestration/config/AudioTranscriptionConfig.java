package com.cortex.cortex_rag_orchestration.config;

import java.time.Duration;

import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AudioTranscriptionConfig {

  @Value("${spring.ai.openai.base-url}")
  private String baseUrl;

  @Value("${spring.ai.openai.api-key}")
  private String apiKey;

  @Bean
  public OpenAiAudioApi audioApi() {
    return OpenAiAudioApi.builder().apiKey(apiKey).baseUrl(baseUrl).restClientBuilder(RestClient.builder()).build();
  }

  @Bean
  public RestClient audioRestClient(RestClient.Builder builder) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

    int timeoutMillis = (int) Duration.ofMinutes(2).toMillis();
    requestFactory.setReadTimeout(timeoutMillis);

    return builder
        .requestFactory(requestFactory)
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .build();
  }
}
