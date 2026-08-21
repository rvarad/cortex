package com.cortex.cortex_media_processing_service.service;

import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
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

  /**
   * Generic streaming upload of a local file to an arbitrary GCS object path.
   * The caller owns the destination path + contentType; this only moves bytes.
   */
  public String uploadFile(String destinationObjectName, Path localFile, String contentType) {
    try {
      log.info("[GCSService] Uploading file to: {}", destinationObjectName);

      BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, destinationObjectName))
          .setContentType(contentType)
          .build();

      try (InputStream inputStream = Files.newInputStream(localFile);
          WriteChannel writer = storage.writer(blobInfo)) {
        byte[] buffer = new byte[64 * 1024];

        int limit;
        while ((limit = inputStream.read(buffer)) >= 0) {
          writer.write(ByteBuffer.wrap(buffer, 0, limit));
        }
      }
      return destinationObjectName;
    } catch (Exception e) {
      log.info("[GCS Storage Service] Failed to upload file to {}", destinationObjectName);
      throw new RuntimeException("[GCS Storage Service] Failed to upload file to " + destinationObjectName, e);
    }
  }

  public String uploadChunk(String objectName, Path chunkToUpload) throws Exception {
    String fileName = chunkToUpload.getFileName().toString();
    String contentType = fileName.toLowerCase().endsWith("mp4") ? "video/mp4" : "audio/wav";

    // Using a "chunks/" folder prefix within the same bucket
    String fullGcsPath = "chunks/" + objectName.replace("uploads/", "") + "/" + fileName;
    return uploadFile(fullGcsPath, chunkToUpload, contentType);
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

  public byte[] readHead(String objectName, int howManyBytes) {
    BlobId blobId = BlobId.of(bucketName, objectName);

    try (ReadChannel reader = storage.reader(blobId)) {
      ByteBuffer bucket = ByteBuffer.allocate(howManyBytes);

      while (bucket.hasRemaining() && reader.read(bucket) != -1) {
      }
      bucket.flip();

      byte[] head = new byte[bucket.remaining()];
      bucket.get(head);

      return head;
    } catch (Exception e) {
      log.error("[GCSService] readHead failed for {}", objectName, e);
      throw new RuntimeException("readHead failed for " + objectName, e);
    }
  }
}
