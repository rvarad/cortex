"use client";

import { useState } from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { Button, buttonVariants } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
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
  XCircle,
  ChevronDown,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { formatFileSize } from "@/lib/helpers";
import { RenameDialog } from "./rename-dialog";
import { DeleteDialog } from "./delete-dialog";
import { PipelineStatus } from "./pipeline-status";
import Link from "next/link";

interface FileCardProps {
  fileId: string;
  displayName: string;
  contentType: string;
  fileSize: number;
  onMutate: () => void;
  showPipelineStatus?: boolean;
  isRejected?: boolean;
}

export function FileCard({
  fileId,
  displayName,
  contentType,
  fileSize,
  onMutate,
  showPipelineStatus = false,
  isRejected: isRejectedFromList = false,
}: FileCardProps) {
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  // `isRejected` comes from the file list, which is a snapshot from page load.
  // A file rejected mid-processing (e.g. over the duration cap) is only known
  // from the live event stream, so track that too.
  const [rejectedLive, setRejectedLive] = useState(false);
  const [rejectionMessage, setRejectionMessage] = useState<string | null>(null);
  const [reasonOpen, setReasonOpen] = useState(false);

  const isRejected = isRejectedFromList || rejectedLive;

  const Icon = contentType.startsWith("video/")
    ? FileVideo
    : contentType.startsWith("audio/")
    ? FileAudio
    : File;

  const mediaType = contentType.startsWith("video/")
    ? "Video"
    : contentType.startsWith("audio/")
    ? "Audio"
    : "File";

  const identity = (
    <>
      <div
        className={cn(
          "rounded-lg p-2 shrink-0 transition-colors",
          isRejected
            ? "bg-red-500/10"
            : "bg-primary/10 group-hover/link:bg-primary/20"
        )}
      >
        <Icon
          className={cn("h-5 w-5", isRejected ? "text-red-500" : "text-primary")}
        />
      </div>
      <div className="min-w-0 flex-1">
        <p
          className={cn(
            "truncate text-sm font-medium transition-colors",
            !isRejected && "group-hover/link:text-primary"
          )}
          title={displayName}
        >
          {displayName}
        </p>
        <div className="mt-1 flex items-center gap-2">
          <Badge variant="secondary" className="text-xs shrink-0">
            {mediaType}
          </Badge>
          <span className="text-xs text-muted-foreground truncate">
            {formatFileSize(fileSize)}
          </span>
        </div>
      </div>
    </>
  );

  return (
    <>
      <Card
        onMouseLeave={() => setReasonOpen(false)}
        className={cn(
          "group relative flex flex-col gap-3 p-4 transition-all",
          isRejected
            ? "border-red-500/20"
            : "hover:shadow-md hover:shadow-primary/5 hover:border-primary/30"
        )}
      >
        <div className="flex items-start justify-between gap-4">
          {isRejected ? (
            // Rejected files have no chunks, no playback, no pipeline page —
            // rendered as plain content so there is nothing to click through to.
            <div className="flex flex-1 items-center gap-3 min-w-0 p-1 -m-1">
              {identity}
            </div>
          ) : (
            <Link
              href={`/files/${fileId}`}
              className="flex flex-1 items-center gap-3 min-w-0 group/link cursor-pointer rounded-lg p-1 -m-1 transition-colors hover:bg-muted/50"
            >
              {identity}
            </Link>
          )}
<DropdownMenu>
  <DropdownMenuTrigger
    nativeButton={true}
    render={
      <Button
        variant="ghost"
        size="icon"
        className="h-8 w-8 opacity-0 transition-opacity group-hover:opacity-100 shrink-0"
      >
        <MoreVertical className="h-4 w-4" />
      </Button>
    }
  />
  <DropdownMenuContent align="end">
    {!isRejected && (
      <DropdownMenuItem
        render={<Link href={`/files/${fileId}/chat`} />}
      >
        <MessageSquare className="mr-2 h-4 w-4" />
        Chat
      </DropdownMenuItem>
    )}
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
        {showPipelineStatus && (
          <PipelineStatus
            fileId={fileId}
            onRejected={(message) => {
              setRejectedLive(true);
              setRejectionMessage(message);
            }}
          />
        )}

        {isRejected && (
          <div className="overflow-hidden rounded-lg border border-red-500/20 bg-red-500/5">
            <button
              type="button"
              disabled={!rejectionMessage}
              aria-expanded={reasonOpen}
              onClick={() => setReasonOpen((open) => !open)}
              className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors enabled:hover:bg-red-500/10 disabled:cursor-default"
            >
              <XCircle className="h-3.5 w-3.5 shrink-0 text-red-500" />
              <span className="flex-1 text-xs font-medium text-red-500">
                Upload rejected
              </span>
              {rejectionMessage && (
                <ChevronDown
                  className={cn(
                    "h-3.5 w-3.5 shrink-0 text-red-500/70 transition-transform duration-200",
                    reasonOpen && "rotate-180"
                  )}
                />
              )}
            </button>

            <AnimatePresence initial={false}>
              {reasonOpen && rejectionMessage && (
                <motion.div
                  initial={{ height: 0, opacity: 0 }}
                  animate={{ height: "auto", opacity: 1 }}
                  exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.18, ease: "easeOut" }}
                  className="overflow-hidden"
                >
                  <p className="border-t border-red-500/15 px-3 py-2 text-xs leading-relaxed text-muted-foreground">
                    {rejectionMessage}
                  </p>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        )}
      </Card>

      <RenameDialog
        fileId={fileId}
        currentName={displayName}
        open={renameOpen}
        onOpenChange={setRenameOpen}
        onRenamed={onMutate}
      />
      <DeleteDialog
        fileId={fileId}
        fileName={displayName}
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        onDeleted={onMutate}
      />
    </>
  );
}
