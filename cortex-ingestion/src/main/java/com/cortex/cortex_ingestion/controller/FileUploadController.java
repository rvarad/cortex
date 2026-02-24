package com.cortex.cortex_ingestion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_ingestion.dto.GetPresignedURLRequestDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.service.MinioStorageService;

@RestController
@RequestMapping("/files")
public class FileUploadController {

  // private final FileStorageService fileStorageService;
  private final MinioStorageService minioStorageService;

  public FileUploadController(MinioStorageService minioStorageService) {
    this.minioStorageService = minioStorageService;
  }

  @PostMapping("/upload")
  public ResponseEntity<GetPresignedURLResponseDTO> generatePresignedUrl(
      @RequestBody GetPresignedURLRequestDTO requestBody) {
    GetPresignedURLResponseDTO uploadUrl = minioStorageService.getPresignedUrl(requestBody.getFilename(),
        requestBody.getContentType(), requestBody.getSize());

    return ResponseEntity.ok(uploadUrl);
  }

}