"use client";

import { useEffect, useState, useRef } from "react";
import { subscribeToPipelineEvents } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import {
  Loader2,
  CheckCircle2,
  Circle,
  Scissors,
  Upload,
  Eye,
  AudioLines,
  Database,
  Rocket,
} from "lucide-react";

interface PipelineEvent {
  fileId: string;
  chunkId?: string;
  chunkIndex?: number;
  eventType: string;
  message?: string;
  metadata?: Record<string, unknown>;
}

const PIPELINE_STAGES = [
  { key: "PIPELINE_STARTED", label: "Pipeline Started", icon: Rocket },
  { key: "CHUNKING_STARTED", label: "Chunking", icon: Scissors },
  { key: "CHUNKING_COMPLETE", label: "Chunking Complete", icon: Scissors },
  { key: "MEDIA_CHUNK_READY", label: "Chunks Uploaded", icon: Upload },
  { key: "VISION_ANALYSIS_COMPLETE", label: "Vision Analysis", icon: Eye },
  { key: "TRANSCRIPTION_COMPLETE", label: "Transcription", icon: AudioLines },
  { key: "EMBEDDING_COMPLETE", label: "Embeddings", icon: Database },
  { key: "PIPELINE_COMPLETE", label: "Complete", icon: CheckCircle2 },
] as const;

interface PipelineStatusProps {
  fileId: string;
}

export function PipelineStatus({ fileId }: PipelineStatusProps) {
  const [events, setEvents] = useState<PipelineEvent[]>([]);
  const [isComplete, setIsComplete] = useState(false);
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = subscribeToPipelineEvents(
      fileId,
      (event) => {
        setIsConnected(true);
        setEvents((prev) => [...prev, event]);
        if (event.eventType === "PIPELINE_COMPLETE") {
          setIsComplete(true);
        }
      },
      () => {
        setIsConnected(false);
      }
    );
    eventSourceRef.current = es;
    return () => {
      es.close();
    };
  }, [fileId]);

  const reachedEvents = new Set(events.map((e) => e.eventType));

  const latestStageIndex = PIPELINE_STAGES.reduce((latest, stage, index) => {
    if (reachedEvents.has(stage.key)) return index;
    return latest;
  }, -1);

  if (events.length === 0 && !isConnected) {
    return null;
  }

  return (
    <div className="mt-3 space-y-2 rounded-lg border border-border/50 bg-card/50 p-3">
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-muted-foreground">
          Processing Pipeline
        </p>
        {isComplete ? (
          <Badge
            variant="default"
            className="bg-green-500/10 text-green-500 text-xs"
          >
            <CheckCircle2 className="mr-1 h-3 w-3" />
            Complete
          </Badge>
        ) : (
          <Badge variant="secondary" className="text-xs">
            <Loader2 className="mr-1 h-3 w-3 animate-spin" />
            Processing
          </Badge>
        )}
      </div>

      <div className="space-y-1">
        {PIPELINE_STAGES.map((stage, index) => {
          const isReached = reachedEvents.has(stage.key);
          const isCurrent = index === latestStageIndex && !isComplete;
          const StageIcon = stage.icon;

          return (
            <div
              key={stage.key}
              className={`flex items-center gap-2 rounded px-2 py-1 text-xs transition-colors ${
                isCurrent
                  ? "bg-primary/10 text-primary"
                  : isReached
                  ? "text-muted-foreground"
                  : "text-muted-foreground/30"
              }`}
            >
              {isCurrent ? (
                <Loader2 className="h-3 w-3 animate-spin" />
              ) : isReached ? (
                <CheckCircle2 className="h-3 w-3 text-green-500" />
              ) : (
                <Circle className="h-3 w-3" />
              )}
              <StageIcon className="h-3 w-3" />
              <span>{stage.label}</span>
            </div>
          );
        })}
      </div>

      {events.length > 0 && (
        <p className="mt-1 truncate text-xs text-muted-foreground/70 italic">
          {events[events.length - 1].message}
        </p>
      )}
    </div>
  );
}
