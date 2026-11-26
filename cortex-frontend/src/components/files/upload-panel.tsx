"use client";

import type { Upload } from "./uploads-provider";

/** The uploads in flight, bottom-right. Plain for now; styled in brick 4. */
export function UploadPanel({ uploads }: { uploads: Upload[] }) {
  return (
    <div className="fixed right-4 bottom-4 z-50 w-80 space-y-2 rounded-xl border border-border bg-card p-3 text-sm">
      {uploads.map((upload) => (
        <div key={upload.id} className="flex items-center justify-between gap-2">
          <span className="truncate" title={upload.name}>
            {upload.name}
          </span>
          <span className="shrink-0 text-muted-foreground">
            {upload.status === "failed"
              ? (upload.error ?? "Failed")
              : upload.status === "done"
                ? "Done"
                : `${Math.floor((upload.sentBytes / upload.totalBytes) * 100)}%`}
          </span>
        </div>
      ))}
    </div>
  );
}
