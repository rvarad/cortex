export function formatFileSize(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

/**
 * Formats a duration as M:SS, or H:MM:SS once it passes an hour — otherwise a
 * 90-minute file reads as "90:00".
 */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const hrs = Math.floor(total / 3600);
  const mins = Math.floor((total % 3600) / 60);
  const secs = total % 60;

  const paddedSecs = secs.toString().padStart(2, "0");
  if (hrs === 0) return `${mins}:${paddedSecs}`;

  return `${hrs}:${mins.toString().padStart(2, "0")}:${paddedSecs}`;
}

/** "video/mp4" -> "Video". The container is noise; the kind of media is not. */
export function formatMediaType(contentType: string): string {
  if (contentType.startsWith("video/")) return "Video";
  if (contentType.startsWith("audio/")) return "Audio";
  return "File";
}

export function getMediaIcon(contentType: string): "video" | "audio" | "file" {
  if (contentType.startsWith("video/")) return "video";
  if (contentType.startsWith("audio/")) return "audio";
  return "file";
}
