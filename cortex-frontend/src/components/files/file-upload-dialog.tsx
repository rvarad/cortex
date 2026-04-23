"use client";

import { useState, useCallback } from "react";
import { cn } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Upload, FileVideo, FileAudio, X, Loader2 } from "lucide-react";
import { getPresignedUrl, uploadToGcs } from "@/lib/api";
import { formatFileSize } from "@/lib/helpers";
import { toast } from "sonner";

interface FileUploadDialogProps {
  onUploadComplete: () => void;
  onFileUploaded?: (fileId: string) => void;
}

export function FileUploadDialog({
  onUploadComplete,
  onFileUploaded,
}: FileUploadDialogProps) {
  const [open, setOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [dragActive, setDragActive] = useState(false);

  const acceptedTypes = [
    "video/mp4",
    "video/webm",
    "video/quicktime",
    "audio/mpeg",
    "audio/wav",
    "audio/ogg",
    "audio/mp4",
  ];

  const handleFile = (file: File) => {
    if (!acceptedTypes.includes(file.type)) {
      toast.error("Unsupported file type. Please upload a video or audio file.");
      return;
    }
    setSelectedFile(file);
  };

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragActive(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  }, []);

  const handleUpload = async () => {
    if (!selectedFile) return;

    setIsUploading(true);
    try {
      // Step 1: Get presigned URL from Gateway
      const { uploadUrl, fileId } = await getPresignedUrl({
        filename: selectedFile.name,
        contentType: selectedFile.type,
        fileSize: selectedFile.size,
      });

      // Step 2: Upload directly to GCS
      await uploadToGcs(uploadUrl, selectedFile);

      if (onFileUploaded) {
        onFileUploaded(fileId);
      }

      toast.success("File uploaded successfully! Processing will begin shortly.");
      setOpen(false);
      setSelectedFile(null);
      onUploadComplete();
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Upload failed. Please try again."
      );
    } finally {
      setIsUploading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        nativeButton={true}
        render={
          <button
            className={cn(buttonVariants({ variant: "default" }), "gap-2")}
            id="upload-button"
          />
        }
      >
        <Upload className="h-4 w-4" />
        Upload Media
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Upload Media File</DialogTitle>
        </DialogHeader>

        {!selectedFile ? (
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={handleDrop}
            className={`flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-10 transition-colors ${
              dragActive
                ? "border-primary bg-primary/5"
                : "border-border hover:border-primary/50"
            }`}
          >
            <Upload className="mb-3 h-10 w-10 text-muted-foreground" />
            <p className="mb-1 text-sm font-medium">
              Drag & drop your file here
            </p>
            <p className="mb-4 text-xs text-muted-foreground">
              or click to browse
            </p>
            <input
              type="file"
              accept="video/*,audio/*"
              className="absolute inset-0 cursor-pointer opacity-0"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) handleFile(file);
              }}
              id="file-input"
            />
            <div className="flex gap-2">
              <span className="flex items-center gap-1 text-xs text-muted-foreground">
                <FileVideo className="h-3 w-3" /> Video
              </span>
              <span className="flex items-center gap-1 text-xs text-muted-foreground">
                <FileAudio className="h-3 w-3" /> Audio
              </span>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between rounded-lg border border-border bg-card p-4">
              <div className="flex items-center gap-3">
                {selectedFile.type.startsWith("video/") ? (
                  <FileVideo className="h-8 w-8 text-primary" />
                ) : (
                  <FileAudio className="h-8 w-8 text-primary" />
                )}
                <div>
                  <p className="text-sm font-medium truncate max-w-[250px]">
                    {selectedFile.name}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {formatFileSize(selectedFile.size)}
                  </p>
                </div>
              </div>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setSelectedFile(null)}
                disabled={isUploading}
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            <Button
              className="w-full gap-2"
              onClick={handleUpload}
              disabled={isUploading}
              id="confirm-upload-button"
            >
              {isUploading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Uploading...
                </>
              ) : (
                <>
                  <Upload className="h-4 w-4" />
                  Upload
                </>
              )}
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
