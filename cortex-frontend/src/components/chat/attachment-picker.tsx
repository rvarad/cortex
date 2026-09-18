"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { FileItem } from "@/lib/types";
import { formatDuration } from "@/lib/helpers";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  Check,
  FileAudio,
  FileVideo,
  Loader2,
  Paperclip,
  Search,
} from "lucide-react";

interface AttachmentPickerProps {
  files: FileItem[];
  attachedIds: string[];
  limit: number;
  onAttach: (file: FileItem) => void;
}

/**
 * The paperclip and the popover it opens. Owns both so an outside-click can
 * tell the trigger apart from the rest of the page — otherwise clicking the
 * paperclip to close it would close-then-reopen.
 */
export function AttachmentPicker({
  files,
  attachedIds,
  limit,
  onAttach,
}: AttachmentPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);

  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const atLimit = attachedIds.length >= limit;

  // Alphabetical, name-only search. Rejected and failed files have nothing to
  // chat about and don't appear; processing ones appear but can't be picked,
  // so the user learns why rather than wondering where the file went.
  const candidates = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return files
      .filter(
        (file) =>
          file.fileStatus !== "REJECTED" && file.fileStatus !== "FAILED"
      )
      .filter(
        (file) =>
          needle === "" || file.fileDisplayName.toLowerCase().includes(needle)
      )
      .sort((a, b) => a.fileDisplayName.localeCompare(b.fileDisplayName));
  }, [files, query]);

  // Clamped at render rather than reset in an effect, so a shrinking result
  // list never leaves the highlight pointing past the end.
  const active = Math.min(highlight, Math.max(0, candidates.length - 1));

  function isAttachable(file: FileItem) {
    return (
      file.fileStatus === "COMPLETED" &&
      !attachedIds.includes(file.fileId) &&
      !atLimit
    );
  }

  function attach(file: FileItem) {
    if (!isAttachable(file)) return;
    onAttach(file);
    // Stay open for the next pick, but there's nothing left to do at the cap.
    if (attachedIds.length + 1 >= limit) setOpen(false);
  }

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;

    function handlePointerDown(event: MouseEvent) {
      const container = containerRef.current;
      if (container && !container.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener("mousedown", handlePointerDown);
    return () => document.removeEventListener("mousedown", handlePointerDown);
  }, [open]);

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        setHighlight(Math.min(active + 1, candidates.length - 1));
        break;
      case "ArrowUp":
        event.preventDefault();
        setHighlight(Math.max(active - 1, 0));
        break;
      case "Enter": {
        event.preventDefault();
        const file = candidates[active];
        if (file) attach(file);
        break;
      }
      case "Escape":
        event.preventDefault();
        setOpen(false);
        break;
    }
  }

  return (
    <div ref={containerRef} className="relative">
      <Button
        variant="outline"
        size="lg"
        onClick={() => setOpen((current) => !current)}
        disabled={atLimit}
        aria-label={
          atLimit ? `Limit reached: ${limit} files` : "Attach files"
        }
        title={atLimit ? `Limit reached: ${limit} files` : "Attach files"}
        aria-expanded={open}
      >
        <Paperclip />
      </Button>

      {open && (
        <div className="absolute bottom-full left-0 z-40 mb-2 w-80 overflow-hidden rounded-xl border border-border bg-popover text-popover-foreground shadow-xl">
          <div className="flex items-center gap-2 border-b border-border/60 px-3 py-2">
            <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
            <input
              ref={inputRef}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setHighlight(0);
              }}
              onKeyDown={handleKeyDown}
              placeholder="Search files…"
              className="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>

          <ul className="max-h-64 overflow-y-auto py-1" role="listbox">
            {candidates.length === 0 && (
              <li className="px-3 py-6 text-center text-xs text-muted-foreground">
                No files match.
              </li>
            )}

            {candidates.map((file, index) => {
              const attached = attachedIds.includes(file.fileId);
              const processing = file.fileStatus !== "COMPLETED";
              const disabled = !isAttachable(file);
              const Icon = file.contentType.startsWith("audio/")
                ? FileAudio
                : FileVideo;

              return (
                <li key={file.fileId} role="option" aria-selected={index === active}>
                  <button
                    type="button"
                    disabled={disabled}
                    onMouseEnter={() => setHighlight(index)}
                    onClick={() => attach(file)}
                    className={cn(
                      "flex w-full items-center gap-2.5 px-3 py-1.5 text-left text-sm transition-colors",
                      index === active && !disabled && "bg-muted",
                      disabled && "cursor-default opacity-50"
                    )}
                  >
                    <Icon className="h-4 w-4 shrink-0 text-primary" />
                    <span className="min-w-0 flex-1 truncate">
                      {file.fileDisplayName}
                    </span>
                    <span className="shrink-0 text-xs text-muted-foreground">
                      {attached ? (
                        <span className="flex items-center gap-1 text-primary">
                          <Check className="h-3 w-3" /> Attached
                        </span>
                      ) : processing ? (
                        <span className="flex items-center gap-1">
                          <Loader2 className="h-3 w-3 animate-spin" /> Processing
                        </span>
                      ) : file.durationSeconds != null ? (
                        formatDuration(file.durationSeconds)
                      ) : null}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>

          <div className="border-t border-border/60 px-3 py-1.5 text-xs text-muted-foreground">
            {attachedIds.length} of {limit} attached
          </div>
        </div>
      )}
    </div>
  );
}
