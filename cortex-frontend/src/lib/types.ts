export interface User {
  userId: string;
  email: string;
  name: string;
  picture: string;
}

/** Mirrors com.cortex.cortex_common.model.FileStatusEnum. */
export type FileStatus =
  | "PENDING"
  | "UPLOADED"
  | "CHUNKED"
  | "PROCESSING"
  | "COMPLETED"
  | "REJECTED"
  | "FAILED";

/** Mirrors com.cortex.cortex_common.model.PlaybackStatusEnum. */
export type PlaybackStatus = "PENDING" | "READY" | "UNAVAILABLE";

/** Mirrors com.cortex.cortex_common.model.UploadRejectReasonEnum. */
export type RejectionReason = "TOO_BIG" | "TOO_LONG" | "CORRUPTED";

export interface FileItem {
  fileId: string;
  fileDisplayName: string;
  objectName: string;
  contentType: string;
  fileSize: number;
  /** Null until the server-side probe runs, so absent while a file is still queued. */
  durationSeconds: number | null;
  fileStatus: FileStatus;
  /** Only set when fileStatus is REJECTED. */
  rejectionReason: RejectionReason | null;
  playbackStatus: PlaybackStatus | null;
}

export interface PlaybackUrlResponse {
  /** Signed GCS URL — a bearer token. Fetch on demand, never cache in a list. */
  playbackUrl: string;
  expiresAt: string;
  hasVideo: boolean | null;
}

export interface PresignedUrlRequest {
  filename: string;
  contentType: string;
  fileSize: number;
}

export interface PresignedUrlResponse {
  uploadUrl: string;
  fileId: string;
  expiresIn: string;
}

export interface UpdateFileRequest {
  displayName: string;
}

export interface SearchRequest {
  query: string;
  fileId?: string;
  languageCode?: string;
  maxResults?: number;
}

export interface SearchResult {
  id: string;
  fileId: string;
  fileDisplayName: string;
  chunkIndex: number;
  startTime: number;
  endTime: number;
  transcript: string;
  visualSummary: string;
  languageCode: string;
  score: number;
}

export interface PipelineEvent {
  eventType: string;
  fileId: string;
  message?: string;
  progress?: number;
  timestamp?: string;
}

/** One piece of a streamed answer. `cites` holds sourceNo values, never array
 *  indices — sources arrive lazily, so arrival order means nothing. */
export interface AnswerSegment {
  text: string;
  cites: number[];
}

/** A citable moment, sent on its own `source` event immediately before the
 *  first segment that cites it. Mirrors SourceRefDTO. */
export interface SourceRef {
  sourceNo: number;
  startTime: number;
  endTime: number;
  fileId: string;
  fileDisplayName: string;
  chunkIndex: number;
}

/**
 * Mirrors com.cortex.cortex_common.model.PipelineEventEnum.
 *
 * The backend sends these as *named* SSE events, and EventSource only delivers
 * names that were explicitly subscribed to — anything missing from this list is
 * silently dropped. Single-sourced here so the subscription and the timeline
 * can't drift apart.
 *
 * PIPELINE_FAILED is listened for ahead of the backend emitting it; an unused
 * subscription costs nothing, a missing one loses the event.
 */
export const PIPELINE_EVENT_TYPES = [
  "PIPELINE_STARTED",
  "PROBE_STARTED",
  "PROBE_COMPLETE",
  "NORMALISATION_STARTED",
  "NORMALISATION_COMPLETE",
  "NORMALISATION_FAILED",
  "CHUNKING_STARTED",
  "CHUNKING_COMPLETE",
  "CHUNK_UPLOAD_STARTED",
  "CHUNK_UPLOAD_COMPLETE",
  "MEDIA_CHUNK_READY",
  "VISION_ANALYSIS_STARTED",
  "VISION_ANALYSIS_COMPLETE",
  "TRANSCRIPTION_STARTED",
  "TRANSCRIPTION_COMPLETE",
  "EMBEDDING_COMPLETE",
  "PIPELINE_COMPLETE",
  "UPLOAD_REJECTED",
  "PIPELINE_FAILED",
] as const;

/**
 * What the ffprobe pass discovered, carried by the PROBE_COMPLETE event.
 * This is the first point at which the pipeline knows the file's real shape —
 * contentType only describes the container, so a silent video still reports
 * "video/mp4" while hasAudio is false.
 */
export interface MediaManifest {
  hasVideo: boolean;
  hasAudio: boolean;
  durationSeconds?: number;
  videoCodec?: string | null;
}

/** One event off the pipeline stream. `eventType` stays a plain string so an
 *  event type the frontend doesn't know about yet still renders in the log. */
export interface PipelineStreamEvent {
  fileId: string;
  chunkId?: string;
  chunkIndex?: number;
  eventType: string;
  message?: string;
  metadata?: Record<string, unknown>;
}
