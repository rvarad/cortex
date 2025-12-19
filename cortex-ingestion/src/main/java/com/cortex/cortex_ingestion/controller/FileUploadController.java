package com.cortex.cortex_ingestion.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cortex.cortex_ingestion.service.FileStorageService;
import com.cortex.cortex_ingestion.service.MinioStorageService;

import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/files")
public class FileUploadController {

  // private final FileStorageService fileStorageService;
  private final MinioStorageService minioStorageService;

  public FileUploadController(MinioStorageService minioStorageService) {
    this.minioStorageService = minioStorageService;
  }

  // @PostMapping("/upload")
  // public ResponseEntity<Map<String, String>> uploadFile(@RequestParam
  // MultipartFile file) {
  // if (file.isEmpty()) {
  // return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
  // }

  // String fileId = fileStorageService.storeFile(file);

  // return ResponseEntity.ok(Map.of(
  // "message", "File uploaded successfully",
  // "fileId", fileId,
  // "originalFileName", file.getOriginalFilename(),
  // "size", String.valueOf(file.getSize()),
  // "contentType", file.getContentType()));
  // }

  @PostMapping("/upload")
  public ResponseEntity<Map<String, String>> generatePresignedUrl(@RequestParam("filename") String filename) {
    String uploadUrl = minioStorageService.getPresignedUrl(filename);

    return ResponseEntity.ok(Map.of(
        "uploadUrl", uploadUrl,
        "expiresIn", "15 minutes"));
  }

}