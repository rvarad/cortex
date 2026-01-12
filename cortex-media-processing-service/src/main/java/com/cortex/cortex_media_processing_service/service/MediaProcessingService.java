package com.cortex.cortex_media_processing_service.service;

import java.io.ByteArrayOutputStream;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import com.cortex.cortex_media_processing_service.utils.WavUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

  private final MinioStorageService minioStorageService;

  private final TranscriptionService transcriptionService;

  private final VisionService visionService;

  private static final int AUDIO_CHUNK_DURATION_US = 60_000_000;
  private static final int VIDEO_SNAPSHOT_INTERVAL_US = 10_000_000;

  public void processMedia(String objectName) {
    String streamUrl = minioStorageService.getPresignedUrl(objectName);

    log.info("streamUrl is : " + streamUrl);

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl)) {

      grabber.setOption("listen", "0");
      grabber.setSampleRate(16000);
      grabber.setAudioChannels(1);

      log.info("Initializing FFmpeg native grabber");
      grabber.start();
      log.info("FFmpeg started. Media format: {}, Duration: {}s", grabber.getFormat(),
          grabber.getLengthInTime() / 1_000_000);

      Java2DFrameConverter imageConverter = new Java2DFrameConverter();

      long nextSnapshotTime = 0;
      long nextAudioChunkTime = 0;
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

          if (timeStamp >= nextAudioChunkTime) {
            // processAudioChunk(audioBuffer.toByteArray(), audioChunkIndex);
            log.info("Processing audio chunk #{}", audioChunkIndex);
            audioBuffer.reset();
            nextAudioChunkTime = nextAudioChunkTime + AUDIO_CHUNK_DURATION_US;
            audioChunkIndex++;
          }
        }
      }

      if (audioBuffer.size() > 0) {
        // processAudioChunk(audioBuffer.toByteArray(), audioChunkIndex);
        log.info("Processing final partial audio chunk.");
      }

      grabber.stop();

      log.info("Processing completed");

    } catch (Exception e) {
      log.error("Processing failed: ", e);
    }
  }

  private void processImage(BufferedImage image, int index, long timeStampUS) {
    CompletableFuture.runAsync(() -> {
      try {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", stream);
        byte[] imageBytes = stream.toByteArray();

        String description = visionService.describe(imageBytes);

        log.info("Described image #{} at {}s: {}", index, timeStampUS / 1000000, description);
      } catch (Exception e) {
        log.error("Failed to process image #{}", index, e);
      }
    });
  }

  private void processAudioChunk(byte[] audioChunk, int index) {
    CompletableFuture.runAsync(() -> {
      try {
        byte[] wavData = WavUtils.addHeader(audioChunk);

        Map<String, Object> response = transcriptionService.transcribe(wavData);

        String transcript = (String) response.get("text");

        String language = (String) response.get("language");

        log.info("Transcribed audio chunk #{} in language {}: {}", index, language, transcript);
      } catch (Exception e) {
        log.error("Transcription failed: ", e);
      }
    });
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