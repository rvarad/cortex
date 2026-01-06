package com.cortex.cortex_common.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileIngestionEventDTO {

  private UUID fileId;
  private String objectName;
  private String contentType;
  private Long fileSize;
  private String fileStatus;

}