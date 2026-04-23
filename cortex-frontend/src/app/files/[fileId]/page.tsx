"use client";

import { useEffect, useState, useRef } from "react";
import { useParams } from "next/navigation";
import { subscribeToPipelineEvents } from "@/lib/api";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Button } from "@/components/ui/button";
import {
  ArrowLeft,
  Loader2,
  CheckCircle2,
  Scissors,
  Eye,
  AudioLines,
  Database,
  Search,
} from "lucide-react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";

interface PipelineEvent {
  fileId: string;
  chunkId?: string;
  chunkIndex?: number;
  eventType: string;
  message?: string;
  metadata?: Record<string, any>;
}

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

export default function GlassboxTimelinePage() {
  const params = useParams();
  const fileId = params.fileId as string;

  const [events, setEvents] = useState<PipelineEvent[]>([]);
  const [totalChunks, setTotalChunks] = useState<number | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [isPipelineComplete, setIsPipelineComplete] = useState(false);

  // Map to hold the state of each chunk
  const [chunks, setChunks] = useState<Record<number, ChunkState>>({});

  useEffect(() => {
    const es = subscribeToPipelineEvents(
      fileId,
      (event) => {
        console.log("[PipelineEvent]", event);
        setIsConnected(true);
        setEvents((prev) => [...prev, event]);

        if (event.eventType === "PIPELINE_COMPLETE") {
          setIsPipelineComplete(true);
        }

        if (event.eventType === "CHUNKING_COMPLETE" && event.metadata?.totalChunks) {
          setTotalChunks(Number(event.metadata.totalChunks));
        }

        // Initialize chunk state if we encounter a chunk index we haven't seen
        if (event.chunkIndex !== undefined) {
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
                if (event.metadata?.visionDescription || event.metadata?.visualSummary) {
                  updated.visualSummary = String(event.metadata.visionDescription || event.metadata.visualSummary);
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
        setIsConnected(false);
      }
    );

    return () => {
      es.close();
    };
  }, [fileId]);

  // Construct an array of chunks to render.
  // If totalChunks is known, we render boxes for all of them.
  // Otherwise, we just map over what we've discovered so far.
  const displayChunks = Array.from(
    { length: totalChunks || Object.keys(chunks).length },
    (_, i) => chunks[i] || { 
      chunkIndex: i, 
      isUploaded: false, 
      visionStatus: "pending", 
      transcriptionStatus: "pending", 
      embeddingStatus: "pending" 
    }
  );

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
            {isPipelineComplete ? (
              <Badge className="bg-green-500/10 text-green-500 hover:bg-green-500/20">
                <CheckCircle2 className="mr-1 h-3 w-3" />
                Processing Complete
              </Badge>
            ) : (
              <Badge variant="secondary" className="animate-pulse">
                <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                Live Feed
              </Badge>
            )}
          </h1>
          <p className="text-sm text-muted-foreground font-mono">
            ID: {fileId}
          </p>
        </div>

        {/* Action button once complete */}
        <AnimatePresence>
          {isPipelineComplete && (
            <motion.div
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
            >
              <Link href={`/files/${fileId}/chat`}>
                <Button className="gap-2 glow-border">
                  <Search className="h-4 w-4" />
                  Chat Details
                </Button>
              </Link>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        
        {/* Main Timeline View */}
        <div className="lg:col-span-3 space-y-6 relative border-l-2 border-border/50 ml-4 pl-8 py-4">
          
          {totalChunks === null && !isPipelineComplete && (
            <div className="flex items-center gap-4 animate-pulse mb-8">
               <div className="absolute -left-[26px] bg-background border-2 border-primary/50 text-primary rounded-full p-2">
                 <Scissors className="h-5 w-5" />
               </div>
               <div>
                 <h3 className="font-medium">Probing & Chunking Media...</h3>
                 <p className="text-sm text-muted-foreground">Calculating required segments</p>
               </div>
            </div>
          )}

          <AnimatePresence>
            {displayChunks.map((chunk, i) => (
              <motion.div
                key={i}
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.3, delay: i * 0.05 }}
                className="relative"
              >
                {/* Node on the timeline */}
                <div className={`absolute -left-[45px] top-6 rounded-full border-2 p-1.5 transition-colors ${
                  chunk.embeddingStatus === "complete" 
                  ? "bg-green-500/10 border-green-500 text-green-500" 
                  : chunk.isUploaded 
                  ? "bg-primary/10 border-primary text-primary"
                  : "bg-background border-border text-muted-foreground"
                }`}>
                  {chunk.embeddingStatus === "complete" ? (
                    <CheckCircle2 className="h-4 w-4" />
                  ) : chunk.isUploaded ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <div className="h-4 w-4 rounded-full bg-border" />
                  )}
                </div>

                <Card className="glass-card overflow-hidden">
                   <div className="border-b border-border/50 bg-muted/20 px-4 py-3 flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <span className="font-semibold text-sm">Chunk {i + 1}</span>
                        {chunk.chunkId && (
                          <span className="text-[10px] uppercase font-mono text-muted-foreground bg-background px-1.5 py-0.5 rounded border border-border">
                            {chunk.chunkId.split("-")[0]}
                          </span>
                        )}
                      </div>
                      
                      {/* Status Badges */}
                      <div className="flex items-center gap-2">
                        {/* Audio */}
                        <Badge variant="outline" className={`text-[10px] gap-1 px-1.5 ${chunk.transcriptionStatus === "complete" ? "border-green-500/50 text-green-500 bg-green-500/5" : chunk.transcriptionStatus === "processing" ? "border-primary/50 text-primary animate-pulse" : "text-muted-foreground/50 border-border"}`}>
                          <AudioLines className="h-3 w-3" />
                          {chunk.transcriptionStatus === 'complete' ? 'Transcribed' : chunk.transcriptionStatus === 'processing' ? 'Listening...' : 'Audio'}
                        </Badge>
                        {/* Vision */}
                        <Badge variant="outline" className={`text-[10px] gap-1 px-1.5 ${chunk.visionStatus === "complete" ? "border-green-500/50 text-green-500 bg-green-500/5" : chunk.visionStatus === "processing" ? "border-primary/50 text-primary animate-pulse" : "text-muted-foreground/50 border-border"}`}>
                          <Eye className="h-3 w-3" />
                          {chunk.visionStatus === 'complete' ? 'Analyzed' : chunk.visionStatus === 'processing' ? 'Watching...' : 'Vision'}
                        </Badge>
                        {/* Embedding */}
                        <Badge variant="outline" className={`text-[10px] gap-1 px-1.5 ${chunk.embeddingStatus === "complete" ? "border-green-500/50 text-green-500 bg-green-500/5" : chunk.embeddingStatus === "processing" ? "border-primary/50 text-primary animate-pulse" : "text-muted-foreground/50 border-border"}`}>
                          <Database className="h-3 w-3" />
                          {chunk.embeddingStatus === 'complete' ? 'Embedded' : chunk.embeddingStatus === 'processing' ? 'Vectorizing...' : 'Vector'}
                        </Badge>
                      </div>
                   </div>

                   {/* Content Area */}
                   <div className="px-4 py-4 grid sm:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <p className="text-xs font-medium flex items-center gap-1 text-muted-foreground">
                          <Eye className="h-3 w-3" /> Visual Summary
                        </p>
                        <div className="text-sm p-3 rounded-lg bg-background/50 border border-border/50 min-h-[80px]">
                           {chunk.visualSummary ? (
                             <span className="text-foreground/90">{chunk.visualSummary}</span>
                           ) : chunk.visionStatus === "processing" ? (
                             <span className="text-muted-foreground/50 flex items-center gap-2 italic">
                               <Loader2 className="h-3 w-3 animate-spin"/> Generating description...
                             </span>
                           ) : (
                             <span className="text-muted-foreground/30 italic">Waiting for chunk upload...</span>
                           )}
                        </div>
                      </div>

                      <div className="space-y-2">
                        <p className="text-xs font-medium flex items-center gap-1 text-muted-foreground">
                          <AudioLines className="h-3 w-3" /> Transcript
                        </p>
                        <div className="text-sm p-3 rounded-lg bg-background/50 border border-border/50 min-h-[80px]">
                           {chunk.transcript ? (
                             <span className="text-foreground/90 italic">"{chunk.transcript}"</span>
                           ) : chunk.transcript === "" ? (
                             <span className="text-muted-foreground/50 italic">[No speech detected]</span>
                           ) : chunk.transcriptionStatus === "processing" ? (
                             <span className="text-muted-foreground/50 flex items-center gap-2 italic">
                               <Loader2 className="h-3 w-3 animate-spin"/> Transcribing audio...
                             </span>
                           ) : (
                             <span className="text-muted-foreground/30 italic">Waiting...</span>
                           )}
                        </div>
                      </div>
                   </div>
                </Card>
              </motion.div>
            ))}
          </AnimatePresence>

          {events.length === 0 && !isConnected && (
            <div className="py-10 text-center text-muted-foreground flex flex-col items-center">
              <Loader2 className="h-8 w-8 animate-spin mb-4 opacity-20" />
              <p>Connecting to event stream...</p>
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
                        <span className="font-mono text-primary font-medium">{ev.eventType}</span>
                        {ev.chunkIndex !== undefined && (
                          <span className="text-muted-foreground bg-muted px-1 rounded">Idx: {ev.chunkIndex}</span>
                        )}
                      </div>
                      {ev.message && <p className="text-muted-foreground">{ev.message}</p>}
                    </motion.div>
                  ))}
                </AnimatePresence>
                {events.length === 0 && (
                  <p className="text-xs text-muted-foreground/50 text-center py-4">Waiting for events...</p>
                )}
              </div>
            </ScrollArea>
          </div>
        </div>
      </div>
    </div>
  );
}
