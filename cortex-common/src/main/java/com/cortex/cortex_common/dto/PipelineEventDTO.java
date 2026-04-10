package com.cortex.cortex_common.dto;

import java.util.Map;
import java.util.UUID;

import com.cortex.cortex_common.model.PipelineEventEnum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineEventDTO {

    @NotNull(message = "fileId must not be null")
    private UUID fileId;

    private UUID chunkId;
    
    private Integer chunkIndex;

    @NotNull(message = "eventType must not be null")
    private PipelineEventEnum eventType;

    @NotBlank(message = "message must not be null or blank")
    private String message;

    @Builder.Default
    private Map<String, Object> metadata = Map.of();
}
