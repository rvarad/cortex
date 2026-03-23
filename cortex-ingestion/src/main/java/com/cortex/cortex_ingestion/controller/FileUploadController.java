package com.cortex.cortex_ingestion.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.dto.FileResponseDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLRequestDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.service.FilesService;
import com.cortex.cortex_ingestion.service.GcsStorageService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/files")
public class FileUploadController {

  private final GcsStorageService gcsStorageService;

  private final FilesService filesService;

  public FileUploadController(GcsStorageService gcsStorageService, FilesService filesService) {
    this.gcsStorageService = gcsStorageService;
    this.filesService = filesService;
  }

  @PostMapping("/upload")
  public ResponseEntity<GetPresignedURLResponseDTO> generatePresignedUrl(
      @RequestBody GetPresignedURLRequestDTO requestBody) {
    log.info("Received request for presigned url for file: {}", requestBody);
    GetPresignedURLResponseDTO uploadUrl = gcsStorageService.getPresignedURL(requestBody.getFilename(),
        requestBody.getContentType());

    return ResponseEntity.ok(uploadUrl);
  }

  @GetMapping
  public ResponseEntity<List<FileResponseDTO>> getAllFiles() {
    List<FileResponseDTO> response = filesService.getAllFiles();

    return ResponseEntity.ok(response);
  }

}