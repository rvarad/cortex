import { PIPELINE_EVENT_TYPES } from "@/lib/types";
import type {
  AnswerSegment,
  ConversationDetail,
  ConversationSummary,
  FileItem,
  PipelineStreamEvent,
  PlaybackUrlResponse,
  SourceRef,
} from "@/lib/types";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const API_VERSION = "/api/v1";

// API_URL is for data endpoints (e.g., https://domain.com/api/v1)
const API_URL = `${API_BASE}${API_VERSION}`;

// Helper to get the root domain for clean auth flows (no /api, no /v1)
const getRootUrl = () => API_BASE.replace(/\/api$/, "");

export class ApiError extends Error {
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
  return request<FileItem[]>("/files");
}

/**
 * Signs a playback URL for one file. Deliberately NOT part of the list payload:
 * every call is a signing operation and the result is a short-lived bearer token,
 * so it is fetched on demand when the user actually asks to play.
 *
 * Throws ApiError with 425 (still preparing), 422 (normalisation failed),
 * 410 (upload rejected) or 404 (not found / not yours).
 */
export async function getPlaybackUrl(fileId: string) {
  return request<PlaybackUrlResponse>(`/files/${fileId}/playback-url`);
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
  onEvent: (event: PipelineStreamEvent) => void,
  onError?: (error: Event) => void
): EventSource {
  const url = `${API_URL}/files/${fileId}/events`;
  const eventSource = new EventSource(url, { withCredentials: true });

  // The backend sends named events via SseEmitter.event().name(eventType).
  // We must listen for each named event type individually.
  for (const type of PIPELINE_EVENT_TYPES) {
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

// ============ Conversations ============

/** Creates an empty conversation. Called on first send, not on page open. */
export async function createConversation() {
  return request<ConversationSummary>("/conversations", { method: "POST" });
}

/** The sidebar, newest activity first (the server orders it). */
export async function listConversations() {
  return request<ConversationSummary[]>("/conversations");
}

/**
 * One conversation for reload. Returns only the most recent messages — the
 * server caps it and exposes no offset, so there is no "load earlier" yet.
 *
 * Throws ApiError 404 for an id that is missing or belongs to someone else;
 * the backend deliberately does not distinguish the two.
 */
export async function getConversation(id: string) {
  return request<ConversationDetail>(`/conversations/${id}`);
}

/** Hard delete; the messages cascade server-side. No undo. */
export async function deleteConversation(id: string) {
  return request<void>(`/conversations/${id}`, { method: "DELETE" });
}

// ============ Chat ============

export interface ChatStreamHandlers {
  /** Something the agent is doing, in words — "Searching library…". */
  onStep: (label: string) => void;
  onSource: (source: SourceRef) => void;
  onSegment: (segment: AnswerSegment) => void;
}

/**
 * Parses one SSE frame — the text between two blank lines — and routes it.
 * Returns true when the frame was the terminal `done` event. Unknown event
 * names are ignored rather than thrown on, so a new backend event type can't
 * break an older client.
 */
function dispatchChatFrame(
  frame: string,
  handlers: ChatStreamHandlers
): boolean {
  let eventName = "message";
  const dataLines: string[] = [];

  for (const line of frame.split("\n")) {
    if (line === "" || line.startsWith(":")) continue;

    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "event") eventName = value;
    else if (field === "data") dataLines.push(value);
  }

  if (eventName === "done") return true;
  if (dataLines.length === 0) return false;

  let payload: unknown;
  try {
    payload = JSON.parse(dataLines.join("\n"));
  } catch {
    return false;
  }

  switch (eventName) {
    case "step": {
      const step = payload as { label?: unknown };
      if (typeof step.label === "string") handlers.onStep(step.label);
      break;
    }
    case "source":
      handlers.onSource(payload as SourceRef);
      break;
    case "segment": {
      const segment = payload as AnswerSegment;
      handlers.onSegment({
        text: segment.text ?? "",
        cites: Array.isArray(segment.cites) ? segment.cites : [],
      });
      break;
    }
  }

  return false;
}

/**
 * Streams an agentic answer: `step` events as the agent searches and reads,
 * then `source`/`segment` pairs as it writes, then `done`.
 *
 * This endpoint is a POST and EventSource can only issue GET requests, so
 * `subscribeToPipelineEvents` cannot be reused here. Frames are read off a
 * ReadableStream and parsed by hand, buffering across chunk boundaries because
 * a single read can split one frame or carry several.
 *
 * Resolves to true when the server sent `done`, false when the stream closed
 * without it — which is how a cut-off answer (emitter timeout, crash mid-run)
 * is told apart from a finished one. Rejects on a non-2xx response or an
 * aborted signal.
 */
export async function streamConversationMessage(
  conversationId: string,
  body: { question: string; fileIds: string[] },
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<boolean> {
  const response = await fetch(
    `${API_URL}/conversations/${conversationId}/messages`,
    {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(body),
    signal,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new ApiError(response.status, text || response.statusText);
  }

  if (!response.body) {
    throw new Error("The chat stream returned no body.");
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  let completed = false;

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer = (buffer + value).replace(/\r\n/g, "\n");

      let boundary = buffer.indexOf("\n\n");
      while (boundary !== -1) {
        if (dispatchChatFrame(buffer.slice(0, boundary), handlers)) {
          completed = true;
        }
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
    }

    // A well-behaved server ends on a blank line, but don't lose a final frame
    // if the connection closes without one.
    if (buffer.trim().length > 0 && dispatchChatFrame(buffer, handlers)) {
      completed = true;
    }
  } finally {
    reader.releaseLock();
  }

  return completed;
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
