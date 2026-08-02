"use client";

import { useEffect, useState, useCallback } from "react";
import { getFiles } from "@/lib/api";
import { FileCard } from "@/components/files/file-card";
import { FileUploadDialog } from "@/components/files/file-upload-dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Inbox } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";

interface FileItem {
  fileId: string;
  fileDisplayName: string;
  objectName: string;
  contentType: string;
  fileSize: number;
  fileStatus: string;
}

export default function DashboardPage() {
  const [files, setFiles] = useState<FileItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [recentUploads, setRecentUploads] = useState<Set<string>>(new Set());

  const fetchFiles = useCallback(async () => {
    try {
      const data = await getFiles();
      setFiles(data);
    } catch (error) {
      console.error("Failed to fetch files:", error);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchFiles();
  }, [fetchFiles]);

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Media Library</h1>
          <p className="mt-1 text-muted-foreground">
            Upload and manage your media files
          </p>
        </div>
        <FileUploadDialog
          onUploadComplete={fetchFiles}
          onFileUploaded={(fileId: string) => {
            setRecentUploads((prev) => {
              const next = new Set(prev);
              next.add(fileId);
              return next;
            });
          }}
        />
      </div>

      {/* File Grid */}
      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-xl" />
          ))}
        </div>
      ) : files.length === 0 ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20"
        >
          <Inbox className="mb-4 h-12 w-12 text-muted-foreground/50" />
          <p className="text-lg font-medium text-muted-foreground">
            No files yet
          </p>
          <p className="mt-1 text-sm text-muted-foreground/70">
            Upload your first media file to get started
          </p>
        </motion.div>
      ) : (
        <AnimatePresence mode="popLayout">
          <motion.div
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
            layout
          >
            {files.map((file) => (
              <motion.div
                key={file.fileId}
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.95 }}
                layout
              >
                <FileCard
                  fileId={file.fileId}
                  displayName={file.fileDisplayName}
                  contentType={file.contentType}
                  fileSize={file.fileSize}
                  onMutate={fetchFiles}
                  isRejected={file.fileStatus === "REJECTED"}
                  showPipelineStatus={
                    file.fileStatus !== "COMPLETED" &&
                    file.fileStatus !== "FAILED"
                  }
                />
              </motion.div>
            ))}
          </motion.div>
        </AnimatePresence>
      )}
    </div>
  );
}
