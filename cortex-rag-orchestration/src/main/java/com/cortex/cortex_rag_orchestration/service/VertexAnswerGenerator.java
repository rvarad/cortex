package com.cortex.cortex_rag_orchestration.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class VertexAnswerGenerator implements AnswerGenerator {

  private final Client genAiClient;

  private final ObjectMapper objectMapper;

  private final RateLimiter inferenceRateLimiter = RateLimiter.create(1);

  private static final String MODEL_NAME = "gemini-2.5-flash";

  private static final Schema RESPONSE_SCHEMA = Schema.builder()
      .type(Type.Known.ARRAY)
      .items(Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(Map.of(
              "text", Schema.builder().type(Type.Known.STRING).build(),
              "cites", Schema.builder()
                  .type(Type.Known.ARRAY)
                  .items(Schema.builder().type(Type.Known.INTEGER).build())
                  .build()))
          .required(List.of("text", "cites"))
          .build())
      .build();

  @Override
  public List<AnswerSegmentDTO> generateAnswer(String prompt) {
    try {
      inferenceRateLimiter.acquire();

      GenerateContentConfig config = GenerateContentConfig.builder()
          .responseMimeType("application/json")
          .responseSchema(RESPONSE_SCHEMA)
          .build();

      String json = genAiClient.models
          .generateContent(MODEL_NAME, Content.fromParts(Part.fromText(prompt)), config)
          .text();

      return objectMapper.readValue(json, new TypeReference<List<AnswerSegmentDTO>>() {
      });
    } catch (Exception e) {
      log.error("Error generating answer for prompt: {}", prompt, e);
      throw new RuntimeException("Error generating answer for prompt: " + prompt, e);
    }
  }
}
