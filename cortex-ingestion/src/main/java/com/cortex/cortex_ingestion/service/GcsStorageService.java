package com.cortex.cortex_ingestion.service;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GcsStorageService {

  private final Storage storage;

  private final FileMetadataRepository fileMetadataRepository;

  private final KafkaProducerService kafkaProducerService;

  @Value("${gcs.bucket}")
  private String bucketName;

  @Transactional
  public GetPresignedURLResponseDTO getPresignedURL(String originalFileName, String contentType, Long fileSize) {
    String extension = "";
    if (originalFileName != null && originalFileName.contains(".")) {
      extension = originalFileName.substring(originalFileName.lastIndexOf("."));
    }

    // Default to 0 if size is not provided
    long safeSize = (fileSize != null) ? fileSize : 0L;

    String objectName = "uploads/media/" + UUID.randomUUID().toString() + extension;
    log.info("[GCSService] Generated objectName: {}", objectName);

    try {
      BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName))
          .setContentType(contentType)
          .build();

      long expirationTime = 30;

      URL url = storage.signUrl(blobInfo, expirationTime, TimeUnit.MINUTES,
          Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
          Storage.SignUrlOption.withExtHeaders(Map.of("Content-Type", contentType)),
          Storage.SignUrlOption.withV4Signature());
      log.info("[GCSService] Generated presigned URL: {}", url);

      FileMetadata fileMetadata = fileMetadataRepository
          .save(FileMetadata.builder().fileDisplayName(originalFileName).fileSize(safeSize).objectName(objectName)
              .bucketName(bucketName).fileStatus(FileStatusEnum.PENDING).contentType(contentType).build());
      log.info("[GCSService] Saved file metadata: {}", fileMetadata);

      return GetPresignedURLResponseDTO.builder().uploadUrl(url.toString())
          .expiresIn(LocalDateTime.now().plusMinutes(expirationTime)).build();

    } catch (Exception e) {
      log.error("[GCSService] Error generating presigned URL: {}", e.getMessage());
      throw new RuntimeException("Error generating presigned URL", e);
    }
  }

  public void handleFileUploadSuccess(String objectName, long size) {
    try {
      String decodedObjectName = URLDecoder.decode(objectName, StandardCharsets.UTF_8);

      FileMetadata fileMetadata = fileMetadataRepository.findByObjectName(decodedObjectName).orElseThrow(() -> {
        log.error("[GCSService] File metadata not found for objectName: {}", decodedObjectName);
        return new RuntimeException("File metadata not found for objectName: " + decodedObjectName);
      });

      // Update size if provided (and greater than 0), as the initial metadata might
      // have had 0 or estimated size
      if (size > 0) {
        fileMetadata.setFileSize(size);
      }

      fileMetadata.setFileStatus(FileStatusEnum.UPLOADED);
      fileMetadataRepository.save(fileMetadata);
      log.info("[GCSService] Updated file metadata: {}", fileMetadata);

      FileIngestionEventDTO event = FileIngestionEventDTO.builder().fileId(fileMetadata.getId())
          .objectName(decodedObjectName).contentType(fileMetadata.getContentType()).fileSize(fileMetadata.getFileSize())
          .fileStatus(fileMetadata.getFileStatus().toString()).build();

      PipelineEventDTO pipelineEventDTO = PipelineEventDTO.builder().fileId(fileMetadata.getId())
          .eventType(PipelineEventEnum.PIPELINE_STARTED).message("File uploaded successfully").metadata(Map.of(
              "contentType", fileMetadata.getContentType(),
              "fileSize", fileMetadata.getFileSize(),
              "objectName", fileMetadata.getObjectName(),
              "bucketName", fileMetadata.getBucketName(),
              "fileStatus", fileMetadata.getFileStatus().toString()))
          .build();

      kafkaProducerService.sendFileIngestedEvent(event);
      kafkaProducerService.sendPipelineEvent(pipelineEventDTO);
      log.info("[GCSService] Sent file upload success event: {}", event);

    } catch (Exception e) {
      log.error("[GCSService] Error sending file upload success event for objectName: {}", objectName, e);
      throw new RuntimeException("Error sending file upload success event for objectName: " + objectName, e);
    }
  }

  public void deleteObject(String objectName) {
    storage.delete(BlobId.of(bucketName, objectName));
    log.info("[GCSService] Deleted object for fileId: {}", objectName);

    String chunksPrefix = "chunks/" + objectName.replace("uploads/", "") + "/";

    Page<Blob> blobs = storage.list(bucketName, Storage.BlobListOption.prefix(chunksPrefix));

    for (Blob blob : blobs.iterateAll()) {
      storage.delete(blob.getBlobId());
      log.info("[GCSService] Deleted chunk for objectName: {}", blob.getName());
    }
  }
}
