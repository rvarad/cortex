package com.cortex.cortex_ingestion.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatus;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.repository.FileMetadataRepository;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

  private final MinioClient minioClient;
  private final MinioClient presignedUrlClient;
  private final FileMetadataRepository fileMetadataRepository;
  private final KafkaProducerService kafkaProducerService;

  @Value("${minio.bucket}")
  private String quarantineBucket;

  @Transactional
  public GetPresignedURLResponseDTO getPresignedUrl(String originalFilename, String contentType, Long size) {
    String extension = "";
    if (originalFilename != null && originalFilename.contains(".")) {
      extension = originalFilename.substring(originalFilename.lastIndexOf("."));
    }

    String objectName = UUID.randomUUID().toString() + extension;

    try {
      java.util.Map<String, String> headers = new java.util.HashMap<>();
      headers.put("Content-Type", contentType);

      String url = presignedUrlClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.PUT)
              .bucket(quarantineBucket)
              .object(objectName)
              .extraHeaders(headers)
              .expiry(20, TimeUnit.MINUTES)
              .build());

      FileMetadata metadata = fileMetadataRepository
          .save(FileMetadata.builder().fileDisplayName(originalFilename).bucketName(quarantineBucket)
              .objectName(objectName).fileSize(size).fileStatus(FileStatus.PENDING).contentType(contentType).build());

      log.info("Generated presigned url for file: {}", url);
      return GetPresignedURLResponseDTO.builder().uploadUrl(url).fileId(metadata.getId())
          .expiresIn(LocalDateTime.now().plusMinutes(20)).build();
    } catch (Exception e) {
      throw new RuntimeException("Error generating presigned url", e);
    }
  }

  @Transactional
  public void handleFileUploadNotification(String objectName) {
    try {
      String decodedObjectName = URLDecoder.decode(objectName, StandardCharsets.UTF_8);

      FileMetadata metadata = fileMetadataRepository.findByObjectName(decodedObjectName)
          .orElseThrow(() -> {
            System.err.println("[MinioService] Error: Metadata NOT FOUND in database for object: " + decodedObjectName);
            return new RuntimeException("File metadata not found for: " + decodedObjectName);
          });

      metadata.setFileStatus(FileStatus.UPLOADED);
      fileMetadataRepository.save(metadata);

      FileIngestionEventDTO event = FileIngestionEventDTO.builder().fileId(metadata.getId())
          .objectName(decodedObjectName).contentType(metadata.getContentType()).fileSize(metadata.getFileSize())
          .fileStatus(metadata.getFileStatus().toString()).build();

      log.info("Sending file ingested event for file: {}", event);

      kafkaProducerService.sendFileIngestedEvent(event);
    } catch (Exception e) {
      throw new RuntimeException("Error handling file upload notification", e);
    }
  }
}
