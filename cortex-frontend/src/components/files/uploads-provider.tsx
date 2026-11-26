"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { toast } from "sonner";
import { deleteFile, getPresignedUrl, uploadToGcs } from "@/lib/api";

export interface Upload {
  /** Local key. The server's fileId only exists once the signed URL is made. */
  id: number;
  fileId: string | null;
  totalBytes: number;
  sentBytes: number;
  /** "done" stays in the list: the file is PENDING until its webhook, and the
   *  dashboard only shows a PENDING file this tab owns. */
  status: "uploading" | "done";
}

interface UploadsContextValue {
  uploads: Upload[];
  start: (file: File) => void;
  /** Stops the send; the file is then deleted, as for a failure. */
  cancel: (id: number) => void;
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
 * Owns every upload in flight. Mounted at the root so an upload survives
 * navigation away from the dashboard, and its tile picks it up again on return.
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
          // Out of the list first, so the tile goes now rather than after the delete.
          setUploads((current) => current.filter((upload) => upload.id !== id));

          // A cancel is the user's own doing; a failure would otherwise be a
          // tile that silently vanishes.
          if (!(error instanceof DOMException && error.name === "AbortError")) {
            toast.error(
              `Upload of ${file.name} failed: ${
                error instanceof Error ? error.message : "unknown error"
              }`,
              { duration: 6000 } // Sonner's 4 s is short for an error that names a file.
            );
          }

          // Marks the half-sent file deleted; the sweep removes it, and any
          // object that landed after all.
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

  // A reload or a closed tab kills the XHR, so the browser asks first. Only a
  // real unload: navigating inside the app keeps the provider and the upload.
  const isUploading = uploads.some((upload) => upload.status === "uploading");
  useEffect(() => {
    if (!isUploading) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [isUploading]);

  const cancel = useCallback((id: number) => {
    controllers.current.get(id)?.abort();
  }, []);

  const value = useMemo<UploadsContextValue>(
    () => ({ uploads, start, cancel, listVersion }),
    [uploads, start, cancel, listVersion]
  );

  return (
    <UploadsContext.Provider value={value}>{children}</UploadsContext.Provider>
  );
}
