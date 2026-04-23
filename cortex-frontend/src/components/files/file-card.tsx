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
} from "lucide-react";
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
}

export function FileCard({
  fileId,
  displayName,
  contentType,
  fileSize,
  onMutate,
  showPipelineStatus = false,
}: FileCardProps) {
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

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

  return (
    <>
      <Card className="group relative flex flex-col gap-3 p-4 transition-all hover:shadow-md hover:shadow-primary/5 hover:border-primary/30">
        <div className="flex items-start justify-between gap-4">
          <Link
            href={`/files/${fileId}`}
            className="flex flex-1 items-center gap-3 min-w-0 group/link cursor-pointer rounded-lg p-1 -m-1 transition-colors hover:bg-muted/50"
          >
            <div className="rounded-lg bg-primary/10 p-2 shrink-0 transition-colors group-hover/link:bg-primary/20">
              <Icon className="h-5 w-5 text-primary" />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium transition-colors group-hover/link:text-primary" title={displayName}>
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
          </Link>
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
    <DropdownMenuItem
      render={<Link href={`/files/${fileId}/chat`} />}
    >
      <MessageSquare className="mr-2 h-4 w-4" />
      Chat
    </DropdownMenuItem>
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
        {showPipelineStatus && <PipelineStatus fileId={fileId} />}
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
