package com.cortex.cortex_common.dto;

import java.util.List;
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

  /** The original chat path's single-file scope. Superseded by {@link #fileIds}; remove with it. */
  private UUID fileId;

  /**
   * Files attached to <em>this message</em>, not to the conversation — the same way Claude and
   * Gemini chat behave. Their content is loaded into the prompt before the agent's first model
   * call. Empty or null on most turns.
   */
  private List<UUID> fileIds;
  // TODO: need language code here, maybe later
}
