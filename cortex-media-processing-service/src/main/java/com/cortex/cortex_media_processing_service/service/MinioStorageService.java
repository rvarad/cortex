package com.cortex.cortex_media_processing_service.service;

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

  public String getPresignedUrl(String objectName) {
    try {
      return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
          .bucket(quarantineBucket).object(objectName).expiry(1, TimeUnit.HOURS).build());
    } catch (Exception e) {
      throw new RuntimeException("Error getting presigned url", e);
    }
  }
}