package com.cortex.cortex_common.model;

public enum PipelineEventEnum {
  PIPELINE_STARTED,
  PROBE_STARTED,
  PROBE_COMPLETE,
  CHUNKING_STARTED,
  CHUNKING_COMPLETE,
  CHUNK_UPLOAD_STARTED,
  CHUNK_UPLOAD_COMPLETE,
  MEDIA_CHUNK_READY,
  VISION_ANALYSIS_STARTED,
  VISION_ANALYSIS_COMPLETE,
  TRANSCRIPTION_STARTED,
  TRANSCRIPTION_COMPLETE,
  EMBEDDING_COMPLETE,
  PIPELINE_COMPLETE,
  PIPELINE_FAILED,
  UPLOAD_REJECTED,
  NORMALISATION_STARTED,
  NORMALISATION_COMPLETE,
  NORMALISATION_FAILED;

  /** The message of a PIPELINE_FAILED for a file that failed by an error (ADR-0015). */
  public static final String FAILED_BY_ERROR_MESSAGE =
      "Processing stopped because of an error on our side. Delete this file and upload it again.";

  /** The message of the sweep's PIPELINE_FAILED for a file that stopped making progress (rule 1). */
  public static final String FAILED_BY_NO_PROGRESS_MESSAGE =
      "Processing stopped making progress. Delete this file and upload it again.";
}

// TODO: Add an error event and have the details in the message. TBD