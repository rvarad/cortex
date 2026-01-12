package com.cortex.cortex_media_processing_service.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavUtils {

  public static byte[] addHeader(byte[] pcmData) {
    // Audio Format Constraints (Must match FFmpeg output)
    int sampleRate = 16000;
    int channels = 1;
    int bitDepth = 16;

    int dataLength = pcmData.length;
    int totalDataLen = dataLength + 36;
    int byteRate = sampleRate * channels * (bitDepth / 8);

    ByteBuffer header = ByteBuffer.allocate(44);
    header.order(ByteOrder.LITTLE_ENDIAN);

    // 1. RIFF Chunk Descriptor
    header.put("RIFF".getBytes());
    header.putInt(totalDataLen);
    header.put("WAVE".getBytes());

    // 2. fmt Sub-Chunk
    header.put("fmt ".getBytes());
    header.putInt(16); // Sub-chunk size (16 for PCM)
    header.putShort((short) 1); // AudioFormat (1 = PCM)
    header.putShort((short) channels);
    header.putInt(sampleRate);
    header.putInt(byteRate);
    header.putShort((short) (channels * (bitDepth / 8))); // BlockAlign
    header.putShort((short) bitDepth);

    // 3. data Sub-Chunk
    header.put("data".getBytes());
    header.putInt(dataLength);

    // 4. Combine Header + Raw Data
    byte[] wavFile = new byte[44 + dataLength];
    System.arraycopy(header.array(), 0, wavFile, 0, 44);
    System.arraycopy(pcmData, 0, wavFile, 44, dataLength);

    return wavFile;
  }
}