package com.cortex.cortex_rag_orchestration.util;

/** A streamed segment that is not the JSON we asked for. Only this means "bad answer". */
public class SegmentParseException extends RuntimeException {

  public SegmentParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
