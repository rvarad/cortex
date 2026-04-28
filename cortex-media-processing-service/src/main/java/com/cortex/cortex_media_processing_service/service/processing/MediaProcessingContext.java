package com.cortex.cortex_media_processing_service.service.processing;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.cortex.cortex_common.dto.MediaFileManifestDTO;

import lombok.Data;

@Data
public class MediaProcessingContext {
  private final UUID fileId;
  private final String objectName;
  private final String userId;
  private final Path workDir;
  private final MediaFileManifestDTO manifestDTO;
  private final AtomicLong lastChunkTimeMS = new AtomicLong(System.currentTimeMillis());
  private final AtomicInteger totalChunks = new AtomicInteger(0);

  private final AtomicBoolean isRunning = new AtomicBoolean(true);
  private final Map<Integer, ChunkPair> chunkPairMap = new ConcurrentHashMap<>();
  private final Map<String, UploadStatus> chunkUploadStatusMap = new ConcurrentHashMap<>();
  private final BlockingQueue<Path> uploadQueue = new LinkedBlockingQueue<>();
}
