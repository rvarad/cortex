package com.cortex.cortex_media_processing_service.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MinioStorageService {

  private final MinioClient minioClient;

  @Value("${minio.bucket.download}")
  private String quarantineBucket;

  @Value("${minio.bucket.chunks}")
  private String chunksBucket;

  public String getPresignedUrl(String objectName) {
    try {
      return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
          .bucket(quarantineBucket).object(objectName).expiry(1, TimeUnit.HOURS).build());
    } catch (Exception e) {
      throw new RuntimeException("Error getting presigned url", e);
    }
  }

  public String uploadChunk(String objectName, Path chunkToUpload) throws Exception {
    long fileSize = Files.size(chunkToUpload);

    String contentType = chunkToUpload.getFileName().toString().toLowerCase().endsWith("mp4") ? "video/mp4"
        : "audio/wav";

    String fullMinioPath = objectName + "/" + chunkToUpload.getFileName().toString();

    try (InputStream inputStream = Files.newInputStream(chunkToUpload)) {
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(chunksBucket)
              .object(fullMinioPath)
              .stream(inputStream, fileSize, -1)
              .contentType(contentType)
              .build());
    }

    return fullMinioPath;
  }

  public InputStream getFileStream(String objectName) {
    try {
      return minioClient.getObject(io.minio.GetObjectArgs.builder()
          .bucket(chunksBucket)
          .object(objectName)
          .build());
    } catch (Exception e) {
      throw new RuntimeException("Error getting file stream", e);
    }
  }

}