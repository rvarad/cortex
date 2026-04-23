export interface User {
  userId: string;
  email: string;
  name: string;
  picture: string;
}

export interface FileItem {
  fileId: string;
  fileDisplayName: string;
  objectName: string;
  contentType: string;
  fileSize: number;
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
