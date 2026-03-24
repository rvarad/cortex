package com.cortex.cortex_ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateFileRequestDTO(
    @NotBlank(message = "Display name cannot be empty")
    @Size(max = 255, message = "Display name cannot exceed 255 characters")
    @Pattern(regexp = "^[a-zA-Z0-9 _\\-@/\\\\().]+$", message = "Display name contains invalid characters")
    String displayName
) {}
