"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from "react";
import { deleteFile, getPresignedUrl, uploadToGcs } from "@/lib/api";
import { UploadPanel } from "./upload-panel";

export type UploadStatus = "uploading" | "done" | "failed";

export interface Upload {
  /** Local key. The server's fileId only exists once the signed URL is made. */
  id: number;
  fileId: string | null;
  name: string;
  contentType: string;
  totalBytes: number;
  sentBytes: number;
  status: UploadStatus;
  error?: string;
}

interface UploadsContextValue {
  uploads: Upload[];
  start: (file: File) => void;
  /** Goes up whenever an upload adds, finishes or deletes a file row. The
   *  dashboard refetches on it — the dialog that started the upload has
   *  already closed, so it can't tell the list to refresh. */
  listVersion: number;
}

const UploadsContext = createContext<UploadsContextValue | null>(null);

export function useUploads(): UploadsContextValue {
  const context = useContext(UploadsContext);
  if (!context) {
    throw new Error("useUploads must be used within an UploadsProvider");
  }
  return context;
}

/**
 * Owns every upload in flight. Mounted at the root so an upload — and its row
 * in the panel — survives navigation away from the dashboard.
 */
export function UploadsProvider({ children }: { children: React.ReactNode }) {
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [listVersion, setListVersion] = useState(0);
  // A counter, not crypto.randomUUID(): that only exists on HTTPS or localhost.
  const nextId = useRef(0);
  const controllers = useRef(new Map<number, AbortController>());

  const update = useCallback((id: number, patch: Partial<Upload>) => {
    setUploads((current) =>
      current.map((upload) =>
        upload.id === id ? { ...upload, ...patch } : upload
      )
    );
  }, []);

  const start = useCallback(
    (file: File) => {
      const id = nextId.current++;
      const controller = new AbortController();
      controllers.current.set(id, controller);

      setUploads((current) => [
        ...current,
        {
          id,
          fileId: null,
          name: file.name,
          contentType: file.type,
          totalBytes: file.size,
          sentBytes: 0,
          status: "uploading",
        },
      ]);

      void (async () => {
        let fileId: string | null = null;
        try {
          const response = await getPresignedUrl({
            filename: file.name,
            contentType: file.type,
            fileSize: file.size,
          });
          fileId = response.fileId;
          update(id, { fileId });
          // The row now exists as PENDING; show its tile.
          setListVersion((version) => version + 1);

          // XHR fires ~20 times a second and every update re-renders all
          // consumers, the dashboard grid included. One per whole percent caps
          // a file at about a hundred.
          let lastPercent = -1;
          await uploadToGcs(
            response.uploadUrl,
            file,
            (sentBytes) => {
              const percent = Math.floor((sentBytes / file.size) * 100);
              if (percent === lastPercent) return;
              lastPercent = percent;
              update(id, { sentBytes });
            },
            controller.signal
          );

          update(id, { status: "done", sentBytes: file.size });
        } catch (error) {
          update(id, {
            status: "failed",
            error: error instanceof Error ? error.message : "Upload failed",
          });

          // A half-sent file has no object behind it; left alone it would sit
          // on the dashboard as PENDING for good.
          if (fileId) {
            await deleteFile(fileId).catch((deleteError) =>
              console.error("Failed to delete abandoned upload:", deleteError)
            );
          }
        } finally {
          controllers.current.delete(id);
          setListVersion((version) => version + 1);
        }
      })();
    },
    [update]
  );

  const value = useMemo<UploadsContextValue>(
    () => ({ uploads, start, listVersion }),
    [uploads, start, listVersion]
  );

  return (
    <UploadsContext.Provider value={value}>
      {children}
      {uploads.length > 0 && <UploadPanel uploads={uploads} />}
    </UploadsContext.Provider>
  );
}
