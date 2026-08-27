"use client";

import { useState } from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { Button, buttonVariants } from "@/components/ui/button";
import { badgeVariants } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  FileVideo,
  FileAudio,
  File,
  MoreVertical,
  Pencil,
  Trash2,
  MessageSquare,
  Play,
  CircleSlash,
  Loader2,
  CheckCircle2,
  XCircle,
  AlertTriangle,
} from "lucide-react";
import { formatFileSize, formatDuration, formatMediaType } from "@/lib/helpers";
import { useMediaPlayer } from "@/components/media/media-player-provider";
import type { FileItem, RejectionReason } from "@/lib/types";
import { RenameDialog } from "./rename-dialog";
import { DeleteDialog } from "./delete-dialog";
import Link from "next/link";

const REJECTION_LABELS: Record<RejectionReason, string> = {
  TOO_BIG: "Too large",
  TOO_LONG: "Too long",
  CORRUPTED: "Corrupted",
};

interface FileCardProps {
  file: FileItem;
  onMutate: () => void;
}

export function FileCard({ file, onMutate }: FileCardProps) {
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const player = useMediaPlayer();

  const isFailure =
    file.fileStatus === "REJECTED" || file.fileStatus === "FAILED";
  const isProcessed = file.fileStatus === "COMPLETED";

  const Icon = file.contentType.startsWith("video/")
    ? FileVideo
    : file.contentType.startsWith("audio/")
    ? FileAudio
    : File;

  // "Video · 12:34 · 84.2 MB" — duration is absent until the server-side probe runs.
  const metaLine = [
    formatMediaType(file.contentType),
    file.durationSeconds != null ? formatDuration(file.durationSeconds) : null,
    formatFileSize(file.fileSize),
  ]
    .filter(Boolean)
    .join(" · ");

  // Playback resolves early — normalisation runs before chunking — so a file can
  // be playable while the rest of the pipeline is still working. The two axes are
  // rendered independently. Rejected and failed files have no object left in GCS,
  // so they get no playback affordance at all.
  const playbackSlot = isFailure ? null : file.playbackStatus === "READY" ? (
    <Button
      size="sm"
      variant={player.openFileId === file.fileId ? "secondary" : "default"}
      onClick={() =>
        player.open({ fileId: file.fileId, title: file.fileDisplayName })
      }
    >
      <Play />
      {player.openFileId === file.fileId ? "Playing" : "Play"}
    </Button>
  ) : file.playbackStatus === "UNAVAILABLE" ? (
    <span
      role="img"
      aria-label="Playback unavailable"
      title="Playback unavailable"
      className="inline-flex size-7 shrink-0 items-center justify-center rounded-lg border border-border text-muted-foreground/60"
    >
      <CircleSlash className="size-3.5" />
    </span>
  ) : (
    <span
      title="Preparing playback…"
      className="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-lg border border-border px-2.5 text-[0.8rem] font-medium text-muted-foreground"
    >
      <Play className="size-3.5 animate-pulse motion-reduce:animate-none" />
      Preparing
    </span>
  );

  // One chip for the whole lifecycle, in a fixed position, always linking to the
  // pipeline history. It changes state rather than disappearing, so the way in
  // that the user learns while a file is processing still works afterwards.
  const statusChip = (() => {
    switch (file.fileStatus) {
      case "COMPLETED":
        return {
          label: "Processed",
          StatusIcon: CheckCircle2,
          spin: false,
          className: "border-green-500/30 text-green-500 hover:bg-green-500/10",
        };
      case "REJECTED":
        return {
          label: file.rejectionReason
            ? `Rejected · ${REJECTION_LABELS[file.rejectionReason]}`
            : "Rejected",
          StatusIcon: XCircle,
          spin: false,
          className: "border-red-500/30 text-red-500 hover:bg-red-500/10",
        };
      case "FAILED":
        return {
          label: "Failed",
          StatusIcon: AlertTriangle,
          spin: false,
          className: "border-red-500/30 text-red-500 hover:bg-red-500/10",
        };
      default:
        return {
          label: "Processing",
          StatusIcon: Loader2,
          spin: true,
          className: "border-border text-muted-foreground hover:bg-muted",
        };
    }
  })();

  return (
    <>
      <Card
        className={cn(
          "group flex flex-col gap-3 p-4 transition-all",
          isFailure
            ? "ring-red-500/20"
            : "hover:shadow-md hover:shadow-primary/5"
        )}
      >
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <div
              className={cn(
                "shrink-0 rounded-lg p-2",
                isFailure ? "bg-red-500/10" : "bg-primary/10"
              )}
            >
              <Icon
                className={cn(
                  "h-5 w-5",
                  isFailure ? "text-red-500" : "text-primary"
                )}
              />
            </div>
            <div className="min-w-0 flex-1">
              <p
                className="truncate text-sm font-medium"
                title={file.fileDisplayName}
              >
                {file.fileDisplayName}
              </p>
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {metaLine}
              </p>
            </div>
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger
              nativeButton={true}
              render={
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="File options"
                  className="h-8 w-8 shrink-0 opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100"
                >
                  <MoreVertical className="h-4 w-4" />
                </Button>
              }
            />
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => setRenameOpen(true)}>
                <Pencil className="mr-2 h-4 w-4" />
                Rename
              </DropdownMenuItem>
              <DropdownMenuItem
                onClick={() => setDeleteOpen(true)}
                variant="destructive"
              >
                <Trash2 className="mr-2 h-4 w-4" />
                Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {playbackSlot}
            {isProcessed && (
              <Link
                href={`/files/${file.fileId}/chat`}
                className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
              >
                <MessageSquare />
                Chat
              </Link>
            )}
          </div>

          <Link
            href={`/files/${file.fileId}`}
            title="View processing history"
            className={cn(
              badgeVariants({ variant: "outline" }),
              "h-6 shrink-0 gap-1.5 px-2.5 transition-colors",
              statusChip.className
            )}
          >
            <statusChip.StatusIcon
              className={cn(statusChip.spin && "animate-spin")}
            />
            {statusChip.label}
          </Link>
        </div>
      </Card>

      <RenameDialog
        fileId={file.fileId}
        currentName={file.fileDisplayName}
        open={renameOpen}
        onOpenChange={setRenameOpen}
        onRenamed={onMutate}
      />
      <DeleteDialog
        fileId={file.fileId}
        fileName={file.fileDisplayName}
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        onDeleted={onMutate}
      />
    </>
  );
}
