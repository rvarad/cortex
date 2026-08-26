"use client";

import { useEffect, useState, useRef } from "react";
import { useParams } from "next/navigation";
import { subscribeToPipelineEvents } from "@/lib/api";
import type { MediaManifest, PipelineStreamEvent } from "@/lib/types";
import { formatDuration } from "@/lib/helpers";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import {
  ArrowLeft,
  Loader2,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Scissors,
  Eye,
  AudioLines,
  Database,
  MessageSquare,
  Rocket,
  Film,
  ScanSearch,
  WifiOff,
} from "lucide-react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";

// Internal representation of a chunk's state for the UI
interface ChunkState {
  chunkIndex: number;
  chunkId?: string;
  isUploaded: boolean;
  visionStatus: "pending" | "processing" | "complete";
  visualSummary?: string;
  transcriptionStatus: "pending" | "processing" | "complete";
  transcript?: string;
  embeddingStatus: "pending" | "processing" | "complete";
}

/** How the run ended. Only these three events terminate a pipeline —
 *  NORMALISATION_FAILED does not: playback is lost but transcription,
 *  embedding and completion carry on without it. */
type Outcome = "running" | "complete" | "rejected" | "failed";

const TERMINAL_EVENTS: Record<string, Exclude<Outcome, "running">> = {
  PIPELINE_COMPLETE: "complete",
  UPLOAD_REJECTED: "rejected",
  PIPELINE_FAILED: "failed",
};

const OUTCOME_BADGES = {
  running: {
    label: "Live Feed",
    Icon: Loader2,
    spin: true,
    className: "",
    variant: "secondary" as const,
  },
  complete: {
    label: "Processing Complete",
    Icon: CheckCircle2,
    spin: false,
    className: "bg-green-500/10 text-green-500",
    variant: "outline" as const,
  },
  rejected: {
    label: "Upload Rejected",
    Icon: XCircle,
    spin: false,
    className: "bg-red-500/10 text-red-500",
    variant: "outline" as const,
  },
  failed: {
    label: "Pipeline Failed",
    Icon: AlertTriangle,
    spin: false,
    className: "bg-red-500/10 text-red-500",
    variant: "outline" as const,
  },
};

interface Stage {
  key: string;
  label: string;
  detail?: string;
  Icon: typeof Rocket;
  state: "done" | "running" | "warned";
}

/**
 * The probe is the only authority on which streams a file actually contains —
 * a silent video is still "video/mp4". Files processed before PROBE_COMPLETE
 * existed fall back to the container type, which keeps their pages rendering
 * as they always did rather than requiring a backfill.
 */
function readManifest(events: PipelineStreamEvent[]): {
  manifest: MediaManifest;
  probed: boolean;
} {
  const probe = events.find((e) => e.eventType === "PROBE_COMPLETE");

  if (probe?.metadata) {
    const meta = probe.metadata;
    return {
      probed: true,
      manifest: {
        hasVideo: Boolean(meta.hasVideo),
        hasAudio: Boolean(meta.hasAudio),
        durationSeconds:
          meta.durationSeconds != null
            ? Number(meta.durationSeconds)
            : undefined,
        videoCodec: meta.videoCodec != null ? String(meta.videoCodec) : null,
      },
    };
  }

  const contentType = events.find((e) => e.eventType === "PIPELINE_STARTED")
    ?.metadata?.contentType;

  if (typeof contentType === "string") {
    return {
      probed: false,
      manifest: {
        hasVideo: contentType.startsWith("video/"),
        hasAudio: true,
      },
    };
  }

  return { probed: false, manifest: { hasVideo: true, hasAudio: true } };
}

function ChunkCard({
  index,
  chunk,
  manifest,
}: {
  index: number;
  chunk?: ChunkState;
  manifest: MediaManifest;
}) {
  // Undiscovered chunks are the predicted ones: the probe told us how many to
  // expect, but no event has arrived for this slot yet.
  const isDiscovered = chunk !== undefined;
  const panelCount = (manifest.hasVideo ? 1 : 0) + (manifest.hasAudio ? 1 : 0);

  return (
    <div className="relative">
      <div
        className={cn(
          "absolute -left-[45px] top-6 rounded-full border-2 p-1.5 transition-colors",
          !isDiscovered
            ? "border-border bg-background text-muted-foreground"
            : chunk.embeddingStatus === "complete"
            ? "border-green-500 bg-green-500/10 text-green-500"
            : "border-primary bg-primary/10 text-primary"
        )}
      >
        {!isDiscovered ? (
          <div className="h-4 w-4 rounded-full bg-border" />
        ) : chunk.embeddingStatus === "complete" ? (
          <CheckCircle2 className="h-4 w-4" />
        ) : (
          <Loader2 className="h-4 w-4 animate-spin" />
        )}
      </div>

      <Card
        className={cn(
          "glass-card overflow-hidden",
          !isDiscovered && "opacity-60"
        )}
      >
        <div className="border-b border-border/50 bg-muted/20 px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="font-semibold text-sm">Chunk {index + 1}</span>
            {chunk?.chunkId && (
              <span className="text-[10px] uppercase font-mono text-muted-foreground bg-background px-1.5 py-0.5 rounded border border-border">
                {chunk.chunkId.split("-")[0]}
              </span>
            )}
          </div>

          {/* Only the stages this file's streams actually go through */}
          <div className="flex items-center gap-2">
            {manifest.hasAudio && (
              <Badge
                variant="outline"
                className={cn(
                  "text-[10px] gap-1 px-1.5",
                  chunk?.transcriptionStatus === "complete"
                    ? "border-green-500/50 text-green-500 bg-green-500/5"
                    : chunk?.transcriptionStatus === "processing"
                    ? "border-primary/50 text-primary animate-pulse"
                    : "text-muted-foreground/50 border-border"
                )}
              >
                <AudioLines className="h-3 w-3" />
                {chunk?.transcriptionStatus === "complete"
                  ? "Transcribed"
                  : chunk?.transcriptionStatus === "processing"
                  ? "Listening..."
                  : "Audio"}
              </Badge>
            )}
            {manifest.hasVideo && (
              <Badge
                variant="outline"
                className={cn(
                  "text-[10px] gap-1 px-1.5",
                  chunk?.visionStatus === "complete"
                    ? "border-green-500/50 text-green-500 bg-green-500/5"
                    : chunk?.visionStatus === "processing"
                    ? "border-primary/50 text-primary animate-pulse"
                    : "text-muted-foreground/50 border-border"
                )}
              >
                <Eye className="h-3 w-3" />
                {chunk?.visionStatus === "complete"
                  ? "Analyzed"
                  : chunk?.visionStatus === "processing"
                  ? "Watching..."
                  : "Vision"}
              </Badge>
            )}
            <Badge
              variant="outline"
              className={cn(
                "text-[10px] gap-1 px-1.5",
                chunk?.embeddingStatus === "complete"
                  ? "border-green-500/50 text-green-500 bg-green-500/5"
                  : "text-muted-foreground/50 border-border"
              )}
            >
              <Database className="h-3 w-3" />
              {chunk?.embeddingStatus === "complete" ? "Embedded" : "Vector"}
            </Badge>
          </div>
        </div>

        <div
          className={cn(
            "px-4 py-4 grid gap-4",
            panelCount > 1 && "sm:grid-cols-2"
          )}
        >
          {manifest.hasVideo && (
            <div className="space-y-2">
              <p className="text-xs font-medium flex items-center gap-1 text-muted-foreground">
                <Eye className="h-3 w-3" /> Visual Summary
              </p>
              <div className="text-sm p-3 rounded-lg bg-background/50 border border-border/50 min-h-[80px]">
                {!isDiscovered ? (
                  <SkeletonLines />
                ) : chunk.visualSummary ? (
                  <span className="text-foreground/90">
                    {chunk.visualSummary}
                  </span>
                ) : chunk.visionStatus === "processing" ? (
                  <span className="text-muted-foreground/50 flex items-center gap-2 italic">
                    <Loader2 className="h-3 w-3 animate-spin" /> Generating
                    description...
                  </span>
                ) : (
                  <SkeletonLines />
                )}
              </div>
            </div>
          )}

          {manifest.hasAudio && (
            <div className="space-y-2">
              <p className="text-xs font-medium flex items-center gap-1 text-muted-foreground">
                <AudioLines className="h-3 w-3" /> Transcript
              </p>
              <div className="text-sm p-3 rounded-lg bg-background/50 border border-border/50 min-h-[80px]">
                {!isDiscovered ? (
                  <SkeletonLines />
                ) : chunk.transcript ? (
                  <span className="text-foreground/90 italic">
                    &ldquo;{chunk.transcript}&rdquo;
                  </span>
                ) : chunk.transcript === "" ? (
                  <span className="text-muted-foreground/50 italic">
                    [No speech detected]
                  </span>
                ) : chunk.transcriptionStatus === "processing" ? (
                  <span className="text-muted-foreground/50 flex items-center gap-2 italic">
                    <Loader2 className="h-3 w-3 animate-spin" /> Transcribing
                    audio...
                  </span>
                ) : (
                  <SkeletonLines />
                )}
              </div>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

function SkeletonLines() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-3 w-full" />
      <Skeleton className="h-3 w-11/12" />
      <Skeleton className="h-3 w-4/6" />
    </div>
  );
}

export default function GlassboxTimelinePage() {
  const params = useParams();
  const fileId = params.fileId as string;

  const [events, setEvents] = useState<PipelineStreamEvent[]>([]);
  const [totalChunks, setTotalChunks] = useState<number | null>(null);
  const [streamErrored, setStreamErrored] = useState(false);
  const [chunks, setChunks] = useState<Record<number, ChunkState>>({});

  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = subscribeToPipelineEvents(
      fileId,
      (event) => {
        setEvents((prev) => [...prev, event]);

        // Terminal events: the server completes its emitter, but close from
        // this side too so EventSource can't attempt a reconnect.
        if (event.eventType in TERMINAL_EVENTS) {
          eventSourceRef.current?.close();
        }

        if (
          event.eventType === "CHUNKING_COMPLETE" &&
          event.metadata?.totalChunks
        ) {
          setTotalChunks(Number(event.metadata.totalChunks));
        }

        // Initialize chunk state if we encounter a chunk index we haven't seen.
        // File-level events serialise chunkIndex as null rather than omitting
        // it, so this must be a null check — `!== undefined` lets them through
        // and files a phantom chunk under the key "null".
        if (event.chunkIndex != null) {
          const index = event.chunkIndex;
          setChunks((prev) => {
            const current = prev[index] || {
              chunkIndex: index,
              chunkId: event.chunkId,
              isUploaded: false,
              visionStatus: "pending",
              transcriptionStatus: "pending",
              embeddingStatus: "pending",
            };

            const updated = { ...current };

            switch (event.eventType) {
              case "CHUNK_UPLOAD_COMPLETE":
              case "MEDIA_CHUNK_READY":
                updated.isUploaded = true;
                if (event.chunkId) updated.chunkId = event.chunkId;
                break;
              case "VISION_ANALYSIS_STARTED":
                updated.visionStatus = "processing";
                break;
              case "VISION_ANALYSIS_COMPLETE":
                updated.visionStatus = "complete";
                // Match the backend field name 'visionDescription'
                if (
                  event.metadata?.visionDescription ||
                  event.metadata?.visualSummary
                ) {
                  updated.visualSummary = String(
                    event.metadata.visionDescription ||
                      event.metadata.visualSummary
                  );
                }
                break;
              case "TRANSCRIPTION_STARTED":
                updated.transcriptionStatus = "processing";
                break;
              case "TRANSCRIPTION_COMPLETE":
                updated.transcriptionStatus = "complete";
                if (event.metadata?.transcript) {
                  updated.transcript = String(event.metadata.transcript);
                }
                break;
              case "EMBEDDING_COMPLETE":
                updated.embeddingStatus = "complete";
                break;
            }

            return { ...prev, [index]: updated };
          });
        }
      },
      () => {
        setStreamErrored(true);
      }
    );

    eventSourceRef.current = es;
    return () => {
      es.close();
    };
  }, [fileId]);

  const seen = new Set(events.map((e) => e.eventType));

  const terminalEvent = events.find((e) => e.eventType in TERMINAL_EVENTS);
  const outcome: Outcome = terminalEvent
    ? TERMINAL_EVENTS[terminalEvent.eventType]
    : "running";
  const isTerminated = outcome !== "running";

  const { manifest, probed } = readManifest(events);

  // Chunks appear as their events arrive; nothing is drawn speculatively. Keyed
  // off the highest index seen rather than a count, so a chunk that reports out
  // of order leaves its predecessors as skeletons instead of shifting the list.
  const discoveredIndices = Object.keys(chunks).map(Number);
  const chunkCount =
    totalChunks ??
    (discoveredIndices.length > 0 ? Math.max(...discoveredIndices) + 1 : 0);

  const streamSummary = [
    [manifest.hasVideo && "video", manifest.hasAudio && "audio"]
      .filter(Boolean)
      .join(" + "),
    manifest.durationSeconds
      ? formatDuration(manifest.durationSeconds)
      : undefined,
    manifest.videoCodec || undefined,
  ]
    .filter(Boolean)
    .join(" · ");

  // Build the file-level spine. Chunk cards below cover the per-chunk work, but
  // everything that happens to the file as a whole was previously visible only
  // in the raw log — which is exactly the part a rejected file consists of.
  const stages: Stage[] = [];

  if (seen.has("PIPELINE_STARTED")) {
    stages.push({
      key: "started",
      label: "Upload received",
      Icon: Rocket,
      state: "done",
    });
  }

  if (probed) {
    stages.push({
      key: "probe",
      label: "Media probed",
      detail: streamSummary || undefined,
      Icon: ScanSearch,
      state: "done",
    });
  } else if (seen.has("PROBE_STARTED")) {
    // Started but never completed. On a terminated run that is the story — a
    // corrupted file is rejected at exactly this point — so it must not keep
    // spinning as though the probe were still going.
    stages.push({
      key: "probe",
      label: isTerminated ? "Probe did not complete" : "Probing media",
      detail: isTerminated
        ? "The file could not be read as playable media."
        : "Reading streams, duration and codecs",
      Icon: ScanSearch,
      state: isTerminated ? "warned" : "running",
    });
  }

  // Absent entirely on the passthrough path — no normalisation, no events.
  if (seen.has("NORMALISATION_FAILED")) {
    stages.push({
      key: "normalisation",
      label: "Playback version unavailable",
      detail:
        "A playable copy could not be generated. Processing continued — the file is still transcribed and searchable.",
      Icon: Film,
      state: "warned",
    });
  } else if (seen.has("NORMALISATION_COMPLETE")) {
    stages.push({
      key: "normalisation",
      label: "Playback version ready",
      Icon: Film,
      state: "done",
    });
  } else if (seen.has("NORMALISATION_STARTED")) {
    stages.push({
      key: "normalisation",
      label: "Generating playback version",
      Icon: Film,
      state: "running",
    });
  }

  if (seen.has("CHUNKING_COMPLETE")) {
    stages.push({
      key: "chunking",
      label: "Chunking complete",
      detail: totalChunks !== null ? `${totalChunks} chunks` : undefined,
      Icon: Scissors,
      state: "done",
    });
  } else if (seen.has("CHUNKING_STARTED")) {
    stages.push({
      key: "chunking",
      label: "Chunking media",
      Icon: Scissors,
      state: "running",
    });
  }

  const badge = OUTCOME_BADGES[outcome];

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-4 py-8">
      {/* Header */}
      <div className="mb-8 flex items-center justify-between">
        <div className="space-y-1">
          <Link
            href="/dashboard"
            className="mb-2 flex items-center gap-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground w-fit"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Library
          </Link>
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-3">
            Processing Timeline
            <Badge
              variant={badge.variant}
              className={cn(
                outcome === "running" && "animate-pulse",
                badge.className
              )}
            >
              <badge.Icon className={cn("mr-1", badge.spin && "animate-spin")} />
              {badge.label}
            </Badge>
          </h1>
          <p className="text-sm text-muted-foreground font-mono">ID: {fileId}</p>
        </div>

        {/* Action button once complete */}
        <AnimatePresence>
          {outcome === "complete" && (
            <motion.div
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
            >
              <Link href={`/files/${fileId}/chat`}>
                <Button className="gap-2 glow-border">
                  <MessageSquare className="h-4 w-4" />
                  Chat
                </Button>
              </Link>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        {/* Main Timeline View */}
        <div className="lg:col-span-3 space-y-6 relative border-l-2 border-border/50 ml-4 pl-8 py-4">
          {/* File-level stages */}
          {stages.map((stage) => (
            <div key={stage.key} className="relative">
              <div
                className={cn(
                  "absolute -left-[45px] top-1 rounded-full border-2 p-1.5",
                  stage.state === "warned"
                    ? "border-amber-500 bg-amber-500/10 text-amber-500"
                    : stage.state === "running"
                    ? "border-primary bg-primary/10 text-primary"
                    : "border-green-500 bg-green-500/10 text-green-500"
                )}
              >
                {stage.state === "running" ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <stage.Icon className="h-4 w-4" />
                )}
              </div>
              <h3 className="font-medium">{stage.label}</h3>
              {stage.detail && (
                <p className="mt-0.5 text-sm text-muted-foreground">
                  {stage.detail}
                </p>
              )}
            </div>
          ))}

          {/* Chunks. Nothing renders before the probe: the number to expect and
              the panels each one needs both come from the manifest. */}
          <AnimatePresence>
            {Array.from({ length: chunkCount }, (_, i) => (
              <motion.div
                key={i}
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.3, delay: Math.min(i, 10) * 0.05 }}
              >
                <ChunkCard index={i} chunk={chunks[i]} manifest={manifest} />
              </motion.div>
            ))}
          </AnimatePresence>

          {/* Terminal outcome — the end of the path, in the main column rather
              than buried in the raw log sidebar (which is hidden below lg). */}
          {isTerminated && outcome !== "complete" && (
            <div className="relative">
              <div className="absolute -left-[45px] top-4 rounded-full border-2 border-red-500 bg-red-500/10 p-1.5 text-red-500">
                {outcome === "rejected" ? (
                  <XCircle className="h-4 w-4" />
                ) : (
                  <AlertTriangle className="h-4 w-4" />
                )}
              </div>
              <Card className="border-red-500/30 bg-red-500/5 p-4">
                <p className="font-medium text-red-500">
                  {outcome === "rejected"
                    ? "Upload rejected"
                    : "Pipeline failed"}
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  {terminalEvent?.message ??
                    (outcome === "rejected"
                      ? "This upload was rejected."
                      : "Processing stopped before it could finish.")}
                </p>
              </Card>
            </div>
          )}

          {outcome === "complete" && (
            <div className="relative">
              <div className="absolute -left-[45px] top-1 rounded-full border-2 border-green-500 bg-green-500/10 p-1.5 text-green-500">
                <CheckCircle2 className="h-4 w-4" />
              </div>
              <h3 className="font-medium text-green-500">Pipeline complete</h3>
              <p className="mt-0.5 text-sm text-muted-foreground">
                Every chunk is transcribed, analysed and embedded.
              </p>
            </div>
          )}

          {events.length === 0 && !streamErrored && (
            <div className="py-10 text-center text-muted-foreground flex flex-col items-center">
              <Loader2 className="h-8 w-8 animate-spin mb-4 opacity-20" />
              <p>Waiting for the pipeline to start...</p>
            </div>
          )}

          {/* The server completes the emitter on a terminal event, which the
              browser also surfaces as an error — so only report a dropped
              connection when the run had not already ended. */}
          {streamErrored && !isTerminated && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <WifiOff className="h-4 w-4" />
              Connection to the event stream was lost. Reload to reconnect.
            </div>
          )}
        </div>

        {/* Right Sidebar: Raw Event Log */}
        <div className="hidden lg:block">
          <div className="sticky top-24 rounded-xl border border-border/50 bg-card/30 overflow-hidden flex flex-col h-[600px]">
            <div className="bg-muted/50 p-3 border-b border-border/50">
              <h3 className="text-sm font-semibold flex items-center gap-2">
                <Database className="h-4 w-4 text-primary" />
                Raw Event Stream
              </h3>
            </div>
            <ScrollArea className="flex-1 min-h-0 p-3">
              <div className="space-y-3">
                <AnimatePresence>
                  {[...events].reverse().map((ev, i) => (
                    <motion.div
                      key={i}
                      initial={{ opacity: 0, y: -10 }}
                      animate={{ opacity: 1, y: 0 }}
                      className="text-xs space-y-1 pb-3 border-b border-border/30 last:border-0"
                    >
                      <div className="flex items-center justify-between">
                        <span
                          className={cn(
                            "font-mono font-medium",
                            ev.eventType in TERMINAL_EVENTS &&
                              ev.eventType !== "PIPELINE_COMPLETE"
                              ? "text-red-500"
                              : "text-primary"
                          )}
                        >
                          {ev.eventType}
                        </span>
                        {ev.chunkIndex != null && (
                          <span className="text-muted-foreground bg-muted px-1 rounded">
                            Chunk {ev.chunkIndex + 1}
                          </span>
                        )}
                      </div>
                      {ev.message && (
                        <p className="text-muted-foreground">{ev.message}</p>
                      )}
                    </motion.div>
                  ))}
                </AnimatePresence>
                {events.length === 0 && (
                  <p className="text-xs text-muted-foreground/50 text-center py-4">
                    Waiting for events...
                  </p>
                )}
              </div>
            </ScrollArea>
          </div>
        </div>
      </div>
    </div>
  );
}