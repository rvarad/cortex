package com.cortex.cortex_ingestion.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MinioStorageService {

  private final MinioClient minioClient;

  @Value("${minio.bucket}")
  private String quarantineBucket;

  // public MinioStorageService(MinioClient minioClient) {
  // this.minioClient = minioClient;
  // }

  public String getPresignedUrl(String originalFilename) {
    String extension = "";
    if (originalFilename != null && originalFilename.contains(".")) {
      extension = originalFilename.substring(originalFilename.lastIndexOf("."));
    }

    String objectName = UUID.randomUUID().toString() + extension;

    try {
      String url = minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(quarantineBucket).object(objectName)
              .expiry(20, TimeUnit.MINUTES).build());

      return url;
    } catch (Exception e) {
      throw new RuntimeException("Error generating presigned url", e);
    }
  }
}
