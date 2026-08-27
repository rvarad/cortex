"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from "react";
import { ApiError, getPlaybackUrl } from "@/lib/api";
import { FloatingDock } from "./floating-dock";
import { AlertTriangle, Loader2 } from "lucide-react";

interface OpenOptions {
  fileId: string;
  title: string;
  /** Seek here once the media has enough metadata to accept it. */
  startAt?: number;
  /** Force the dock attached (true) or free (false). Omit to leave it as-is. */
  attached?: boolean;
  /** Defaults to true — pressing Play should play. Pass false to load the media
   *  into the dock ready to use without starting it. */
  autoPlay?: boolean;
}

interface MediaPlayerContextValue {
  open: (options: OpenOptions) => void;
  close: () => void;
  /** Seeks the open media and plays. Expands the dock if it was collapsed. */
  seek: (seconds: number) => void;
  /** Which file the dock currently holds, if any. */
  openFileId: string | null;
  /** False while loading or when playback could not be resolved. */
  canSeek: boolean;
}

type PlayerState =
  | { status: "idle" }
  | { status: "loading"; fileId: string; title: string }
  | {
      status: "ready";
      fileId: string;
      title: string;
      url: string;
      hasVideo: boolean;
      autoPlay: boolean;
    }
  | { status: "error"; fileId: string; title: string; message: string };

const MediaPlayerContext = createContext<MediaPlayerContextValue | null>(null);

export function useMediaPlayer(): MediaPlayerContextValue {
  const context = useContext(MediaPlayerContext);
  if (!context) {
    throw new Error("useMediaPlayer must be used within a MediaPlayerProvider");
  }
  return context;
}

function playbackErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 425:
        return "Playback is still being prepared. Try again in a moment.";
      case 422:
        return "Playback isn't available for this file.";
      case 410:
        return "This upload was rejected and is no longer available.";
      case 404:
        return "File not found.";
    }
  }
  return "Couldn't start playback.";
}

export function MediaPlayerProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [state, setState] = useState<PlayerState>({ status: "idle" });
  const [collapsed, setCollapsed] = useState(false);
  const [attached, setAttached] = useState(false);

  const mediaRef = useRef<HTMLMediaElement | null>(null);
  // A seek can be requested before the element exists or before it knows its
  // duration; hold it here and apply it on loadedmetadata.
  const pendingSeek = useRef<number | null>(null);
  // Guards against a slow request for one file resolving after the user has
  // opened another.
  const requestedFileId = useRef<string | null>(null);

  const applyPendingSeek = useCallback(() => {
    const element = mediaRef.current;
    const target = pendingSeek.current;
    if (!element || target === null) return;

    pendingSeek.current = null;
    element.currentTime = target;
    void element.play().catch(() => {
      // Autoplay can be refused; the user still has the controls.
    });
  }, []);

  const seek = useCallback(
    (seconds: number) => {
      pendingSeek.current = seconds;
      setCollapsed(false);

      // readyState 0 means metadata hasn't landed and currentTime won't stick.
      if (mediaRef.current && mediaRef.current.readyState > 0) {
        applyPendingSeek();
      }
    },
    [applyPendingSeek]
  );

  const open = useCallback(
    (options: OpenOptions) => {
      setCollapsed(false);

      if (options.attached !== undefined) {
        setAttached(options.attached);
      }

      if (options.startAt !== undefined) {
        pendingSeek.current = options.startAt;
      }

      // Already showing this file — raise and seek rather than re-signing a URL.
      if (requestedFileId.current === options.fileId) {
        if (options.startAt !== undefined) seek(options.startAt);
        return;
      }

      requestedFileId.current = options.fileId;
      setState({
        status: "loading",
        fileId: options.fileId,
        title: options.title,
      });

      void (async () => {
        try {
          const response = await getPlaybackUrl(options.fileId);
          if (requestedFileId.current !== options.fileId) return;

          setState({
            status: "ready",
            fileId: options.fileId,
            title: options.title,
            url: response.playbackUrl,
            hasVideo: response.hasVideo ?? true,
            autoPlay: options.autoPlay ?? true,
          });
        } catch (error) {
          if (requestedFileId.current !== options.fileId) return;

          setState({
            status: "error",
            fileId: options.fileId,
            title: options.title,
            message: playbackErrorMessage(error),
          });
        }
      })();
    },
    [seek]
  );

  const close = useCallback(() => {
    requestedFileId.current = null;
    pendingSeek.current = null;
    mediaRef.current = null;
    setState({ status: "idle" });
  }, []);

  const value = useMemo<MediaPlayerContextValue>(
    () => ({
      open,
      close,
      seek,
      openFileId: state.status === "idle" ? null : state.fileId,
      canSeek: state.status === "ready",
    }),
    [open, close, seek, state]
  );

  return (
    <MediaPlayerContext.Provider value={value}>
      {children}

      {state.status !== "idle" && (
        <FloatingDock
          title={state.title}
          collapsed={collapsed}
          onCollapsedChange={setCollapsed}
          attached={attached}
          onAttachedChange={setAttached}
          onClose={close}
        >
          {state.status === "loading" && (
            <div className="flex h-32 items-center justify-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              Preparing playback…
            </div>
          )}

          {state.status === "error" && (
            <div className="flex h-32 items-center gap-3 px-4 text-sm">
              <AlertTriangle className="h-4 w-4 shrink-0 text-amber-500" />
              <span className="text-muted-foreground">{state.message}</span>
            </div>
          )}

          {state.status === "ready" &&
            // No crossOrigin: a plain element range-requests the signed GCS URL
            // without a preflight. Setting it turns on CORS enforcement and the
            // media silently fails to load.
            (state.hasVideo ? (
              <video
                ref={(element) => {
                  mediaRef.current = element;
                }}
                src={state.url}
                controls
                autoPlay={state.autoPlay}
                playsInline
                onLoadedMetadata={applyPendingSeek}
                className="aspect-video w-full bg-black"
              />
            ) : (
              <div className="px-3 py-4">
                <audio
                  ref={(element) => {
                    mediaRef.current = element;
                  }}
                  src={state.url}
                  controls
                  autoPlay={state.autoPlay}
                  onLoadedMetadata={applyPendingSeek}
                  className="w-full"
                />
              </div>
            ))}
        </FloatingDock>
      )}
    </MediaPlayerContext.Provider>
  );
}