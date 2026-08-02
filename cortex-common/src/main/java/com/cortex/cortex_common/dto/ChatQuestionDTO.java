package com.cortex.cortex_common.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatQuestionDTO {

  @NotBlank(message = "Question cannot be empty")
  private String question;
  private UUID fileId;
  // TODO: need language code here, maybe later
}
