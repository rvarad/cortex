package com.cortex.cortex_media_processing_service.service;

import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.WriteChannel;
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

  @Value("${gcs.bucket}")
  private String bucketName;

  public String getPresignedUrl(String objectName) {
    try {
      log.info("[GCSService] Generating GET presigned URL for: {}", objectName);

      BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectName)).build();

      URL url = storage.signUrl(blobInfo, 30, TimeUnit.MINUTES, Storage.SignUrlOption.httpMethod(HttpMethod.GET),
          Storage.SignUrlOption.withV4Signature());

      return url.toString();
    } catch (Exception e) {
      log.error("[GCSService] Error generating presigned URL for: {}", objectName, e);
      throw new RuntimeException("Error generating presigned URL", e);
    }
  }

  public String uploadChunk(String objectName, Path chunkToUpload) throws Exception {
    String fileName = chunkToUpload.getFileName().toString();
    String contentType = fileName.toLowerCase().endsWith("mp4") ? "video/mp4" : "audio/wav";

    // Using a "chunks/" folder prefix within the same bucket
    String fullGcsPath = "chunks/" + objectName + "/" + fileName;
    log.info("[GCSService] Uploading chunk to folder: {}", fullGcsPath);
    BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, fullGcsPath))
        .setContentType(contentType)
        .build();
    try (InputStream inputStream = Files.newInputStream(chunkToUpload);
        WriteChannel writer = storage.writer(blobInfo)) {
      byte[] buffer = new byte[64 * 1024];

      int limit;
      while ((limit = inputStream.read(buffer)) >= 0) {
        writer.write(ByteBuffer.wrap(buffer, 0, limit));
      }
    }
    return fullGcsPath;
  }
}
