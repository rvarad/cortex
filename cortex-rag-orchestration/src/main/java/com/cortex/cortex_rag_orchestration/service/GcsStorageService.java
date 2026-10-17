package com.cortex.cortex_rag_orchestration.service;

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
    log.info("[GCSService] Getting file stream for: {}", objectName);
    return Channels.newInputStream(storage.reader(BlobId.of(bucketName, objectName)));
  }

  public long getFileSize(String objectName) {
    Blob blob = storage.get(BlobId.of(bucketName, objectName));
    if (blob == null) {
      throw new IllegalStateException("Blob not found: " + objectName);
    }
    return blob.getSize();
  }
}
