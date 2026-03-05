package com.cortex.rag_orchestration.service;

import java.io.InputStream;
import java.nio.channels.Channels;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GcsStorageService {

  private final Storage storage;

  @Value("${gcs.bucket}")
  private String bucketName;

  public InputStream getFileStream(String objectName) {
    try {
      log.info("[GCSService] Getting file stream for: {}", objectName);
      return Channels.newInputStream(storage.reader(BlobId.of(bucketName, objectName)));
    } catch (Exception e) {
      log.error("[GCSService] Error getting file stream for: {}", objectName, e);
      throw new RuntimeException("Error getting file stream", e);
    }
  }

  public long getFileSize(String objectName) {
    try {
      Blob blob = storage.get(BlobId.of(bucketName, objectName));
      if (blob == null) {
        throw new RuntimeException("Blob not found: " + objectName);
      }
      return blob.getSize();
    } catch (Exception e) {
      log.error("[GCSService] Error getting file size for: {}", objectName, e);
      throw new RuntimeException("Error getting file size", e);
    }
  }
}
