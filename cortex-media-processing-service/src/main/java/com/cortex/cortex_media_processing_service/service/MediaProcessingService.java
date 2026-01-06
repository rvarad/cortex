package com.cortex.cortex_media_processing_service.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.ShortBuffer;
import java.awt.image.BufferedImage;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

  private final MinioStorageService minioStorageService;

  private static final int AUDIO_CHUNK_DURATION_US = 60_000_000;
  private static final int VIDEO_SNAPSHOT_INTERVAL_US = 5_000_000;

  public void processMedia(String objectName) {
    String streamUrl = minioStorageService.getPresignedUrl(objectName);

    log.info("streamUrl is : " + streamUrl);

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl)) {

      grabber.setOption("listen", "0");

      log.info("Initializing FFmpeg native grabber");
      grabber.start();
      log.info("FFmpeg started. Media format: {}, Duration: {}s", grabber.getFormat(),
          grabber.getLengthInTime() / 1_000_000);

      Java2DFrameConverter imageConverter = new Java2DFrameConverter();

      long nextSnapshotTime = 0;
      ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
      int audioChunkIndex = 0;
      int videoFrameIndex = 0;

      Frame frame;

      log.info("Entering demux loop");

      while ((frame = grabber.grab()) != null) {

        long timeStamp = grabber.getTimestamp();

        if (frame.image != null) {
          if (timeStamp >= nextSnapshotTime) {
            BufferedImage bufferedImage = imageConverter.getBufferedImage(frame);

            processImage(bufferedImage, videoFrameIndex, timeStamp);

            nextSnapshotTime = nextSnapshotTime + VIDEO_SNAPSHOT_INTERVAL_US;
            videoFrameIndex++;
          }
        }

        if (frame.samples != null) {
          byte[] pcmData = convertSampleToBytes(frame);
          audioBuffer.write(pcmData);

          if (audioBuffer.size() >= (16000 * 2 * 60)) {
            processAudioChunk(audioBuffer.toByteArray(), audioChunkIndex);
            audioBuffer.reset();
            audioChunkIndex++;
          }
        }
      }

      if (audioBuffer.size() > 0) {
        processAudioChunk(audioBuffer.toByteArray(), audioChunkIndex);
        log.info("Processing final partial audio chunk.");
      }

      grabber.stop();

      log.info("Processing completed");

    } catch (Exception e) {
      log.error("Processing failed: ", e);
    }
  }

  private void processImage(BufferedImage image, int index, long timeStampUS) {
    int width = image.getWidth();
    int height = image.getHeight();
    double seconds = timeStampUS / 1_000_000.0;

    log.info("Captured frame #{} at {}s with res: {}X{}", index, String.format("%.2f", seconds), width, height);

  }

  private void processAudioChunk(byte[] audioChunk, int index) {
    int sizeInKB = audioChunk.length / 1024;

    log.info("Processed audio chunk #{} with size {}KB", index, sizeInKB);
  }

  private byte[] convertSampleToBytes(Frame frame) {
    ShortBuffer sb = (ShortBuffer) frame.samples[0];
    int samples = sb.limit();
    byte[] bytes = new byte[samples * 2];

    for (int i = 0; i < samples; i++) {
      short sample = sb.get(i);
      bytes[i * 2] = (byte) (sample & 0xff);
      bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
    }

    return bytes;
  }

}