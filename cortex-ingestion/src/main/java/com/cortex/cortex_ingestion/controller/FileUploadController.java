package com.cortex.cortex_ingestion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.dto.GetPresignedURLRequestDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.service.GcsStorageService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/files")
public class FileUploadController {

  // private final FileStorageService fileStorageService;
  private final GcsStorageService gcsStorageService;

  public FileUploadController(GcsStorageService gcsStorageService) {
    this.gcsStorageService = gcsStorageService;
  }

  @PostMapping("/upload")
  public ResponseEntity<GetPresignedURLResponseDTO> generatePresignedUrl(
      @RequestBody GetPresignedURLRequestDTO requestBody) {
    log.info("Received request for presigned url for file: {}", requestBody);
    GetPresignedURLResponseDTO uploadUrl = gcsStorageService.getPresignedURL(requestBody.getFilename(),
        requestBody.getContentType(), requestBody.getSize());

    return ResponseEntity.ok(uploadUrl);
  }

}