"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, getFiles, streamChat } from "@/lib/api";
import type { AnswerSegment, SourceRef } from "@/lib/types";
import { formatDuration } from "@/lib/helpers";
import { useMediaPlayer } from "@/components/media/media-player-provider";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  ArrowLeft,
  FileVideo,
  Loader2,
  MessageSquare,
  Paperclip,
  Send,
  Square,
  TriangleAlert,
} from "lucide-react";
import Link from "next/link";

interface UserMessage {
  role: "user";
  text: string;
}

interface AssistantMessage {
  role: "assistant";
  segments: AnswerSegment[];
  /** Every source the answer cited, in arrival order, keyed by sourceNo. */
  sources: SourceRef[];
  status: "streaming" | "done" | "error";
  error?: string;
}

type Message = UserMessage | AssistantMessage;

function chatErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return "Your session expired. Sign in again.";
    if (error.status === 400) return "That question couldn't be sent.";
    return "The assistant failed to answer. Please try again.";
  }
  return "The connection dropped before the answer finished.";
}

/** Replaces the trailing assistant message, which is the one being streamed. */
function updateLastAssistant(
  messages: Message[],
  update: (message: AssistantMessage) => AssistantMessage
): Message[] {
  const index = messages.length - 1;
  const last = messages[index];
  if (!last || last.role !== "assistant") return messages;

  const next = messages.slice();
  next[index] = update(last);
  return next;
}

interface ChatPanelProps {
  /** Attaches this file as the conversation's context. Omitted means the whole
   *  library — the backend already treats a null fileId as library-wide. */
  fileId?: string;
}

export function ChatPanel({ fileId }: ChatPanelProps) {
  const { open } = useMediaPlayer();

  const [attachedName, setAttachedName] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Resolve the attached file and hand it to the dock, loaded but not playing.
  useEffect(() => {
    if (!fileId) return;

    let cancelled = false;

    void (async () => {
      try {
        const files = await getFiles();
        if (cancelled) return;

        const match = files.find((file) => file.fileId === fileId);
        if (!match) return;

        setAttachedName(match.fileDisplayName);
        open({
          fileId: match.fileId,
          title: match.fileDisplayName,
          attached: true,
          autoPlay: false,
        });
      } catch {
        // The chat still works without the player; leave the chip unresolved.
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [fileId, open]);

  // Abandon an in-flight answer if the user navigates away.
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  useEffect(() => {
    const element = scrollRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [messages]);

  /** A citation jumps to the cited moment; a source opens that file's start. */
  function playSource(source: SourceRef, startAt: number) {
    open({
      fileId: source.fileId,
      title: source.fileDisplayName,
      startAt,
    });
  }

  async function send() {
    const question = input.trim();
    if (!question || isStreaming) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setInput("");
    if (textareaRef.current) textareaRef.current.style.height = "auto";

    setMessages((previous) => [
      ...previous,
      { role: "user", text: question },
      { role: "assistant", segments: [], sources: [], status: "streaming" },
    ]);
    setIsStreaming(true);

    try {
      await streamChat(
        { question, fileId },
        {
          onSegment: (segment) =>
            setMessages((previous) =>
              updateLastAssistant(previous, (message) => ({
                ...message,
                segments: [...message.segments, segment],
              }))
            ),
          onSource: (source) =>
            setMessages((previous) =>
              updateLastAssistant(previous, (message) =>
                message.sources.some((s) => s.sourceNo === source.sourceNo)
                  ? message
                  : { ...message, sources: [...message.sources, source] }
              )
            ),
        },
        controller.signal
      );

      setMessages((previous) =>
        updateLastAssistant(previous, (message) => ({
          ...message,
          status: "done",
        }))
      );
    } catch (error) {
      // A user-initiated stop is not a failure — keep whatever streamed in.
      const stopped = controller.signal.aborted;
      setMessages((previous) =>
        updateLastAssistant(previous, (message) => ({
          ...message,
          status: stopped ? "done" : "error",
          error: stopped ? undefined : chatErrorMessage(error),
        }))
      );
    } finally {
      setIsStreaming(false);
    }
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void send();
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col px-4 py-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <Link
          href={fileId ? "/dashboard" : "/dashboard"}
          className="flex w-fit items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to files
        </Link>

        {fileId && (
          <span
            className="flex min-w-0 items-center gap-1.5 rounded-lg border border-border bg-muted/40 px-2 py-1 text-xs text-muted-foreground"
            title={attachedName ?? undefined}
          >
            <Paperclip className="h-3 w-3 shrink-0" />
            <span className="truncate">{attachedName ?? "Attaching…"}</span>
          </span>
        )}
      </div>

      <div ref={scrollRef} className="flex-1 space-y-6 overflow-y-auto pb-4">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <div className="mb-4 rounded-xl bg-primary/10 p-4">
              <MessageSquare className="h-8 w-8 text-primary" />
            </div>
            <h2 className="text-lg font-semibold">
              {fileId ? "Chat with this file" : "Chat with your library"}
            </h2>
            <p className="mt-1 max-w-sm text-sm text-muted-foreground">
              {fileId
                ? "Ask anything about this file. Answers cite the moments they came from."
                : "Ask across everything you've uploaded. Answers cite the files and moments they came from."}
            </p>
          </div>
        )}

        {messages.map((message, index) =>
          message.role === "user" ? (
            <div key={index} className="flex justify-end">
              <p className="max-w-[85%] rounded-2xl bg-muted px-4 py-2 text-sm whitespace-pre-wrap">
                {message.text}
              </p>
            </div>
          ) : (
            <AssistantBubble
              key={index}
              message={message}
              onCite={(source) => playSource(source, source.startTime)}
              onOpenSource={(source) => playSource(source, 0)}
            />
          )
        )}
      </div>

      <div className="sticky bottom-0 flex items-end gap-2 border-t border-border/50 bg-background pt-3">
        <textarea
          ref={textareaRef}
          rows={1}
          value={input}
          onChange={(event) => {
            setInput(event.target.value);
            const element = event.target;
            element.style.height = "auto";
            element.style.height = `${Math.min(element.scrollHeight, 160)}px`;
          }}
          onKeyDown={handleKeyDown}
          placeholder={
            fileId ? "Ask about this file…" : "Ask about your library…"
          }
          className="max-h-40 flex-1 resize-none rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
        />

        {isStreaming ? (
          <Button
            variant="outline"
            size="lg"
            onClick={() => abortRef.current?.abort()}
            aria-label="Stop generating"
          >
            <Square className="fill-current" />
          </Button>
        ) : (
          <Button
            size="lg"
            onClick={() => void send()}
            disabled={input.trim().length === 0}
            aria-label="Send question"
          >
            <Send />
          </Button>
        )}
      </div>
    </div>
  );
}

function AssistantBubble({
  message,
  onCite,
  onOpenSource,
}: {
  message: AssistantMessage;
  onCite: (source: SourceRef) => void;
  onOpenSource: (source: SourceRef) => void;
}) {
  const byNumber = new Map(message.sources.map((s) => [s.sourceNo, s]));

  // The sources list is per FILE, not per citation: an answer citing six
  // moments from one video lists that video once. First appearance wins, so the
  // order matches the order the answer referenced them in.
  const files = new Map<string, SourceRef>();
  for (const source of message.sources) {
    if (!files.has(source.fileId)) files.set(source.fileId, source);
  }

  const isEmpty =
    message.segments.length === 0 && message.status === "streaming";

  return (
    <div className="space-y-3">
      <div className="text-sm leading-relaxed">
        {isEmpty ? (
          <span className="flex items-center gap-2 text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
            Thinking…
          </span>
        ) : (
          <p className="whitespace-pre-wrap">
            {message.segments.map((segment, index) => (
              <span key={index}>
                {segment.text}
                {segment.cites.map((cite) => {
                  const source = byNumber.get(cite);
                  return (
                    <button
                      key={cite}
                      type="button"
                      disabled={!source}
                      onClick={() => source && onCite(source)}
                      title={
                        source
                          ? `${source.fileDisplayName} · ${formatDuration(
                              source.startTime
                            )}`
                          : undefined
                      }
                      className="mx-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded bg-primary/15 px-1 align-super text-[10px] font-medium text-primary transition-colors hover:bg-primary/30 disabled:opacity-40"
                    >
                      {cite}
                    </button>
                  );
                })}{" "}
              </span>
            ))}
            {message.status === "streaming" && (
              <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse bg-primary align-middle" />
            )}
          </p>
        )}
      </div>

      {files.size > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-muted-foreground">Sources</p>
          <div className="flex flex-wrap gap-1.5">
            {Array.from(files.values()).map((source) => (
              <button
                key={source.fileId}
                type="button"
                onClick={() => onOpenSource(source)}
                title={`Play ${source.fileDisplayName} from the start`}
                className="flex max-w-full items-center gap-1.5 rounded-lg border border-border bg-card px-2 py-1 text-xs transition-colors hover:border-primary/40 hover:bg-muted"
              >
                <FileVideo className="h-3 w-3 shrink-0 text-primary" />
                <span className="truncate">{source.fileDisplayName}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {message.status === "error" && (
        <p
          className={cn(
            "flex items-center gap-2 rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2 text-xs text-red-500"
          )}
        >
          <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
          {message.error}
        </p>
      )}
    </div>
  );
}