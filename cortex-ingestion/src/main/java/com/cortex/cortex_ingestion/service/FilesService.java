package com.cortex.cortex_ingestion.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.dto.FileResponseDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilesService {

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final GcsStorageService gcsStorageService;

  public List<FileResponseDTO> getAllFiles() {
    try {
      List<FileResponseDTO> fileMetadataList = fileMetadataRepository.findAll().stream().map((FileMetadata file) -> {
        return FileResponseDTO.builder().fileId(file.getId()).fileDisplayName(file.getFileDisplayName())
            .objectName(file.getObjectName()).contentType(file.getContentType()).fileSize(file.getFileSize())
            .build();
      }).collect(Collectors.toList());

      return fileMetadataList;
    } catch (Exception e) {
      log.error("[FilesService] Error getting all files: {}", e.getMessage());
      throw new RuntimeException("Error getting all files", e);
    }
  }
}
