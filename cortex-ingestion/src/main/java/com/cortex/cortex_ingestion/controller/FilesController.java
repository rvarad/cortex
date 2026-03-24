package com.cortex.cortex_ingestion.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.dto.FileResponseDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLRequestDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.dto.UpdateFileRequestDTO;
import com.cortex.cortex_ingestion.service.FilesService;
import com.cortex.cortex_ingestion.service.GcsStorageService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/files")
public class FilesController {

  private final GcsStorageService gcsStorageService;

  private final FilesService filesService;

  public FilesController(GcsStorageService gcsStorageService, FilesService filesService) {
    this.gcsStorageService = gcsStorageService;
    this.filesService = filesService;
  }

  @PostMapping("/upload")
  public ResponseEntity<GetPresignedURLResponseDTO> generatePresignedUrl(
      @RequestBody GetPresignedURLRequestDTO requestBody) {
    log.info("Received request for presigned url for file: {}", requestBody);
    GetPresignedURLResponseDTO uploadUrl = gcsStorageService.getPresignedURL(requestBody.getFilename(),
        requestBody.getContentType(), requestBody.getFileSize());

    return ResponseEntity.ok(uploadUrl);
  }

  @GetMapping
  public ResponseEntity<List<FileResponseDTO>> getAllFiles() {
    List<FileResponseDTO> response = filesService.getAllFiles();

    return ResponseEntity.ok(response);
  }

  @DeleteMapping("/{fileId}")
  public ResponseEntity<Void> deleteFile(@PathVariable UUID fileId) {
    log.info("Received request to delete file: {}", fileId);
    filesService.deleteFile(fileId);

    return ResponseEntity.noContent().build();
  }

  @PatchMapping("update/{fileId}")
  public ResponseEntity<Void> updateFile(
      @PathVariable UUID fileId,
      @Valid @RequestBody UpdateFileRequestDTO request) {

    log.info("Received request to update file display name for fileId: {} to '{}'", fileId, request.displayName());
    filesService.updateFileDisplayName(fileId, request.displayName());
    return ResponseEntity.noContent().build();
  }
}