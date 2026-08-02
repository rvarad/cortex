const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const API_VERSION = "/api/v1";

// API_URL is for data endpoints (e.g., https://domain.com/api/v1)
const API_URL = `${API_BASE}${API_VERSION}`;

// Helper to get the root domain for clean auth flows (no /api, no /v1)
const getRootUrl = () => API_BASE.replace(/\/api$/, "");

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const url = endpoint.startsWith("http") ? endpoint : `${API_URL}${endpoint}`;

  const res = await fetch(url, {
    ...options,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json",
      ...options.headers,
    },
  });

  if (res.status === 401) {
    throw new ApiError(401, "Unauthorized");
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "Unknown error");
    throw new ApiError(res.status, text);
  }

  // Handle 204 No Content
  if (res.status === 204) {
    return undefined as T;
  }

  const contentType = res.headers.get("content-type");
  if (contentType && contentType.includes("application/json")) {
    return res.json();
  }

  return undefined as T;
}

// ============ Auth ============

export function getLoginUrl(): string {
  // Use clean URLs (no /api) for browser-based auth flows
  return `${getRootUrl()}/oauth2/authorization/google`;
}

export function getLogoutUrl(): string {
  return `${getRootUrl()}/auth/logout`;
}

export async function getCurrentUser() {
  return request<{
    userId: string;
    email: string;
    name: string;
    picture: string;
  }>(`${getRootUrl()}/auth/me`);
}

// ============ Files ============

export async function getFiles() {
  return request<
    {
      fileId: string;
      fileDisplayName: string;
      objectName: string;
      contentType: string;
      fileSize: number;
      fileStatus: string;
    }[]
  >("/files");
}

export async function getPresignedUrl(body: {
  filename: string;
  contentType: string;
  fileSize: number;
}) {
  return request<{
    uploadUrl: string;
    fileId: string;
    expiresIn: string;
  }>("/files/upload", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function uploadToGcs(
  uploadUrl: string,
  file: File
): Promise<void> {
  // Direct upload to GCS — no credentials, no JSON content-type
  const res = await fetch(uploadUrl, {
    method: "PUT",
    headers: {
      "Content-Type": file.type,
    },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`GCS upload failed: ${res.status}`);
  }
}

export async function updateFileName(fileId: string, displayName: string) {
  return request<void>(`/files/update/${fileId}`, {
    method: "PATCH",
    body: JSON.stringify({ displayName }),
  });
}

export async function deleteFile(fileId: string) {
  return request<void>(`/files/${fileId}`, {
    method: "DELETE",
  });
}

export function subscribeToPipelineEvents(
  fileId: string,
  onEvent: (event: {
    fileId: string;
    chunkId?: string;
    chunkIndex?: number;
    eventType: string;
    message?: string;
    metadata?: Record<string, unknown>;
  }) => void,
  onError?: (error: Event) => void
): EventSource {
  const url = `${API_URL}/files/${fileId}/events`;
  const eventSource = new EventSource(url, { withCredentials: true });

  // The backend sends named events via SseEmitter.event().name(eventType).
  // We must listen for each named event type individually.
  const eventTypes = [
    "PIPELINE_STARTED",
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
  ];

  for (const type of eventTypes) {
    eventSource.addEventListener(type, (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        onEvent(data);
      } catch {
        onEvent({ fileId, eventType: type, message: event.data });
      }
    });
  }

  // Also catch unnamed events as a fallback
  eventSource.onmessage = (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data);
      onEvent(data);
    } catch {
      onEvent({ fileId, eventType: "UNKNOWN", message: event.data });
    }
  };

  eventSource.onerror = (error) => {
    if (onError) onError(error);
    eventSource.close();
  };

  return eventSource;
}

// ============ Search ============

export async function search(body: {
  query: string;
  fileId?: string;
  languageCode?: string;
  maxResults?: number;
}) {
  return request<
    {
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
    }[]
  >("/search", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
