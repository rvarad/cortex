package com.cortex.cortex_ingestion.service;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.cortex.cortex_common.dto.FileIngestionEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.model.PlaybackStatusEnum;
import com.cortex.cortex_common.model.UploadRejectReasonEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.dto.PlaybackUrlResponseDTO;
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

  @Value("${cortex.media.max-file-size-bytes}")
  private Long maxFileBytes;

  @Value("${cortex.media.playback-url-expiry-hours}")
  private int playbackUrlExpiryHours;

  private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("video/mp4", "video/webm", "audio/wav", "audio/mpeg");

  @Transactional
  public GetPresignedURLResponseDTO getPresignedURL(String originalFileName, String contentType, Long fileSize,
      String userId) {

    if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported content type: " + contentType);
    }

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
              .bucketName(bucketName).fileStatus(FileStatusEnum.PENDING).contentType(contentType).userId(userId)
              .build());
      MDC.put("fileId", fileMetadata.getId().toString());
      log.info("[GCSService] Saved file metadata: {}", fileMetadata);

      return GetPresignedURLResponseDTO.builder().uploadUrl(url.toString())
          .expiresIn(LocalDateTime.now().plusMinutes(expirationTime)).build();

    } catch (Exception e) {
      log.error("[GCSService] Error generating presigned URL: {}", e.getMessage());
      throw new RuntimeException("Error generating presigned URL", e);
    } finally {
      MDC.remove("fileId");
    }
  }

  public void handleFileUploadSuccess(String objectName) {
    try {
      String decodedObjectName = URLDecoder.decode(objectName, StandardCharsets.UTF_8);

      FileMetadata fileMetadata = fileMetadataRepository.findByObjectName(decodedObjectName).orElseThrow(() -> {
        log.error("[GCSService] File metadata not found for objectName: {}", decodedObjectName);
        return new RuntimeException("File metadata not found for objectName: " + decodedObjectName);
      });

      MDC.put("fileId", fileMetadata.getId().toString());

      Blob blob = storage.get(BlobId.of(bucketName, decodedObjectName),
          Storage.BlobGetOption.fields(Storage.BlobField.SIZE));

      if (blob == null) {
        log.error("[GCSService] Object not found in GCS for objectName: {}", decodedObjectName);
        throw new RuntimeException("Object not found in GCS for objectName: " + decodedObjectName);
      }

      long actualSize = blob.getSize();

      if (actualSize <= maxFileBytes) {
        fileMetadata.setFileSize(actualSize);
      } else {
        deleteObject(decodedObjectName, fileMetadata.getId());
        fileMetadata.setFileSize(actualSize);
        fileMetadata.setFileStatus(FileStatusEnum.REJECTED);
        fileMetadata.setRejectionReason(UploadRejectReasonEnum.TOO_BIG);
        fileMetadataRepository.save(fileMetadata);
        log.error("[GCSService] File too big: {}", decodedObjectName);

        PipelineEventDTO pipelineEventDTO = PipelineEventDTO.builder().fileId(fileMetadata.getId())
            .eventType(PipelineEventEnum.UPLOAD_REJECTED)
            .message(
                "File is too large to be processed, please upload a file smaller than " + (maxFileBytes / 1024 / 1024)
                    + " MB. This file with size " + (fileMetadata.getFileSize() / 1024 / 1024) + " MB will be deleted.")
            .metadata(Map.of(
                "contentType", fileMetadata.getContentType(),
                "fileSize", actualSize,
                "objectName", fileMetadata.getObjectName(),
                "bucketName", fileMetadata.getBucketName(),
                "fileStatus", fileMetadata.getFileStatus().toString()))
            .build();

        kafkaProducerService.sendPipelineEvent(pipelineEventDTO);
        return;
      }

      fileMetadata.setFileStatus(FileStatusEnum.UPLOADED);
      fileMetadataRepository.save(fileMetadata);
      log.info("[GCSService] Updated file metadata: {}", fileMetadata);

      FileIngestionEventDTO event = FileIngestionEventDTO.builder().fileId(fileMetadata.getId())
          .objectName(decodedObjectName).contentType(fileMetadata.getContentType()).fileSize(fileMetadata.getFileSize())
          .fileStatus(fileMetadata.getFileStatus().toString()).userId(fileMetadata.getUserId()).build();

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
      throw new RuntimeException("Error sending file uploPlad success event for objectName: " + objectName, e);
    } finally {
      MDC.remove("fileId");
    }
  }

  public void deleteObject(String objectName, UUID fileId) {
    storage.delete(BlobId.of(bucketName, objectName));
    log.info("[GCSService] Deleted object for fileId: {}", objectName);

    deletePrefix("chunks/" + objectName.replace("uploads/", "") + "/");

    deletePrefix("playback/" + fileId);
  }

  private void deletePrefix(String prefix) {
    Page<Blob> blobs = storage.list(bucketName, Storage.BlobListOption.prefix(prefix));
    for (Blob blob : blobs.iterateAll()) {
      storage.delete(blob.getBlobId());
      log.info("[GCSService] Deleted object: {}", blob.getName());
    }
  }

  public PlaybackUrlResponseDTO signPlaybackUrl(UUID fileId, String userId) {
    FileMetadata fileMetadata = fileMetadataRepository.findByIdAndUserId(fileId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));

    if (fileMetadata.getFileStatus() == FileStatusEnum.REJECTED) {
      throw new ResponseStatusException(HttpStatus.GONE,
          "This upload was rejected and is no longer available.");
    }

    if (fileMetadata.getPlaybackStatus() == PlaybackStatusEnum.PENDING)
      throw new ResponseStatusException(HttpStatus.TOO_EARLY, "File still processing");

    if (fileMetadata.getPlaybackStatus() == PlaybackStatusEnum.UNAVAILABLE)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Normalisation failed");

    String playbackObject = fileMetadata.getPlaybackObjectName();

    try {
      BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, playbackObject)).build();
      URL url = storage.signUrl(blobInfo, playbackUrlExpiryHours, TimeUnit.HOURS,
          Storage.SignUrlOption.httpMethod(HttpMethod.GET), Storage.SignUrlOption.withV4Signature());

      Instant expiresAt = Instant.now().plus(Duration.ofHours(playbackUrlExpiryHours));

      return PlaybackUrlResponseDTO.builder().playbackUrl(url.toString()).expiresAt(expiresAt)
          .hasVideo(fileMetadata.getHasVideo()).build();

    } catch (Exception e) {
      log.error("[GCSService] Error getting playback url for fileId : {}", fileId, e);
      throw new RuntimeException("Error getting playback url for fileId : " + fileId + " : " + e);
    }
  }
}
