"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, getFiles, streamAgentChat } from "@/lib/api";
import type { AnswerSegment, FileItem, SourceRef } from "@/lib/types";
import { formatDuration } from "@/lib/helpers";
import { useMediaPlayer } from "@/components/media/media-player-provider";
import { AttachmentPicker } from "./attachment-picker";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  ArrowLeft,
  Check,
  FileAudio,
  FileVideo,
  Loader2,
  MessageSquare,
  Paperclip,
  Play,
  Plus,
  Send,
  Square,
  TriangleAlert,
  X,
} from "lucide-react";
import Link from "next/link";

const MAX_ATTACHMENTS = 3;

interface Attachment {
  fileId: string;
  fileDisplayName: string;
}

interface UserMessage {
  role: "user";
  text: string;
  /** What was attached to this question. Attachments clear after each send,
   *  so the history has to carry them or the provenance below means nothing. */
  attachments: Attachment[];
}

interface AgentStep {
  label: string;
  /** A step that arrived after the answer had started — today that's only the
   *  agent giving up at its iteration cap. Rendered as a notice under the
   *  answer, not in the thinking timeline above it. */
  afterAnswer: boolean;
}

interface AssistantMessage {
  role: "assistant";
  steps: AgentStep[];
  segments: AnswerSegment[];
  /** Every source the answer cited, in arrival order, keyed by sourceNo. */
  sources: SourceRef[];
  /** Which files were attached to the question — what "discovered" is
   *  measured against when marking provenance. */
  attachedFileIds: string[];
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
  /** Pre-attaches this file to the first question. It's an ordinary chip from
   *  there — removable, and cleared on send like any other. */
  fileId?: string;
}

export function ChatPanel({ fileId }: ChatPanelProps) {
  const { open } = useMediaPlayer();

  const [files, setFiles] = useState<FileItem[]>([]);
  const [attached, setAttached] = useState<Attachment[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const attachedIds = attached.map((a) => a.fileId);
  const atLimit = attached.length >= MAX_ATTACHMENTS;

  // The picker needs the library either way; the route's file, if any, is
  // pre-attached and handed to the dock loaded but not playing.
  useEffect(() => {
    let cancelled = false;

    void (async () => {
      try {
        const list = await getFiles();
        if (cancelled) return;
        setFiles(list);

        const match = fileId
          ? list.find((file) => file.fileId === fileId)
          : undefined;
        if (!match) return;

        setAttached([
          { fileId: match.fileId, fileDisplayName: match.fileDisplayName },
        ]);
        open({
          fileId: match.fileId,
          title: match.fileDisplayName,
          attached: true,
          autoPlay: false,
        });
      } catch {
        // The chat still works without the list; the picker is just empty.
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

  function attach(file: Pick<FileItem, "fileId" | "fileDisplayName">) {
    setAttached((current) => {
      if (current.length >= MAX_ATTACHMENTS) return current;
      if (current.some((a) => a.fileId === file.fileId)) return current;
      return [
        ...current,
        { fileId: file.fileId, fileDisplayName: file.fileDisplayName },
      ];
    });
  }

  function detach(id: string) {
    setAttached((current) => current.filter((a) => a.fileId !== id));
  }

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

    // Snapshot what this question is scoped to, then clear the chips — the
    // next question starts unscoped.
    const scope = attached;
    const fileIds = scope.map((a) => a.fileId);

    setInput("");
    setAttached([]);
    if (textareaRef.current) textareaRef.current.style.height = "auto";

    setMessages((previous) => [
      ...previous,
      { role: "user", text: question, attachments: scope },
      {
        role: "assistant",
        steps: [],
        segments: [],
        sources: [],
        attachedFileIds: fileIds,
        status: "streaming",
      },
    ]);
    setIsStreaming(true);

    try {
      const completed = await streamAgentChat(
        { question, fileIds },
        {
          onStep: (label) =>
            setMessages((previous) =>
              updateLastAssistant(previous, (message) => ({
                ...message,
                steps: [
                  ...message.steps,
                  { label, afterAnswer: message.segments.length > 0 },
                ],
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
          onSegment: (segment) =>
            setMessages((previous) =>
              updateLastAssistant(previous, (message) => ({
                ...message,
                segments: [...message.segments, segment],
              }))
            ),
        },
        controller.signal
      );

      // The server ends every finished run with `done`. A close without it is
      // the emitter timing out or the agent crashing mid-run — the text that
      // arrived is real, but it isn't the whole answer.
      setMessages((previous) =>
        updateLastAssistant(previous, (message) => ({
          ...message,
          status: completed ? "done" : "error",
          error: completed
            ? undefined
            : "The answer was cut off before it finished.",
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
      <div className="mb-4">
        <Link
          href="/dashboard"
          className="flex w-fit items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to files
        </Link>
      </div>

      <div ref={scrollRef} className="flex-1 space-y-6 overflow-y-auto pb-4">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <div className="mb-4 rounded-xl bg-primary/10 p-4">
              <MessageSquare className="h-8 w-8 text-primary" />
            </div>
            <h2 className="text-lg font-semibold">Chat with your library</h2>
            <p className="mt-1 max-w-sm text-sm text-muted-foreground">
              Attach up to {MAX_ATTACHMENTS} files to focus a question, or ask
              across everything. Answers cite the moments they came from.
            </p>
          </div>
        )}

        {messages.map((message, index) =>
          message.role === "user" ? (
            <UserBubble key={index} message={message} />
          ) : (
            <AssistantBubble
              key={index}
              message={message}
              files={files}
              attachedIds={attachedIds}
              atLimit={atLimit}
              onCite={(source) => playSource(source, source.startTime)}
              onPlay={(source) => playSource(source, 0)}
              onAttach={attach}
            />
          )
        )}
      </div>

      <div className="sticky bottom-0 border-t border-border/50 bg-background pt-3">
        {attached.length > 0 && (
          <div className="mb-2 flex flex-wrap items-center gap-1.5">
            {attached.map((item) => (
              <span
                key={item.fileId}
                className="flex max-w-60 items-center gap-1.5 rounded-lg border border-border bg-muted/40 py-1 pr-1 pl-2 text-xs"
                title={item.fileDisplayName}
              >
                <Paperclip className="h-3 w-3 shrink-0 text-muted-foreground" />
                <span className="truncate">{item.fileDisplayName}</span>
                <button
                  type="button"
                  onClick={() => detach(item.fileId)}
                  aria-label={`Remove ${item.fileDisplayName}`}
                  className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            ))}
            <span className="ml-auto text-xs text-muted-foreground">
              {attached.length}/{MAX_ATTACHMENTS}
            </span>
          </div>
        )}

        <div className="flex items-end gap-2">
          <AttachmentPicker
            files={files}
            attachedIds={attachedIds}
            limit={MAX_ATTACHMENTS}
            onAttach={attach}
          />

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
              attached.length > 0
                ? "Ask about the attached files…"
                : "Ask about your library…"
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
    </div>
  );
}

function UserBubble({ message }: { message: UserMessage }) {
  return (
    <div className="flex flex-col items-end gap-1.5">
      {message.attachments.length > 0 && (
        <div className="flex max-w-[85%] flex-wrap justify-end gap-1">
          {message.attachments.map((item) => (
            <span
              key={item.fileId}
              className="flex max-w-48 items-center gap-1 rounded-md border border-border/60 px-1.5 py-0.5 text-[11px] text-muted-foreground"
              title={item.fileDisplayName}
            >
              <Paperclip className="h-2.5 w-2.5 shrink-0" />
              <span className="truncate">{item.fileDisplayName}</span>
            </span>
          ))}
        </div>
      )}
      <p className="max-w-[85%] rounded-2xl bg-muted px-4 py-2 text-sm whitespace-pre-wrap">
        {message.text}
      </p>
    </div>
  );
}

function AssistantBubble({
  message,
  files,
  attachedIds,
  atLimit,
  onCite,
  onPlay,
  onAttach,
}: {
  message: AssistantMessage;
  files: FileItem[];
  attachedIds: string[];
  atLimit: boolean;
  onCite: (source: SourceRef) => void;
  onPlay: (source: SourceRef) => void;
  onAttach: (file: Pick<FileItem, "fileId" | "fileDisplayName">) => void;
}) {
  const byNumber = new Map(message.sources.map((s) => [s.sourceNo, s]));

  // Tiles are for what the agent found on its own. Attached files are already
  // on screen as the question's chips, so listing them again says nothing.
  // One tile per file: first appearance wins, so order follows the answer.
  const discovered = new Map<string, SourceRef>();
  for (const source of message.sources) {
    if (!source.attached && !discovered.has(source.fileId)) {
      discovered.set(source.fileId, source);
    }
  }

  // "Not in your attached files" is only a warning relative to an attachment.
  const hadAttachments = message.attachedFileIds.length > 0;
  const broadenedToLibrary = hadAttachments && discovered.size > 0;

  const timeline = message.steps.filter((step) => !step.afterAnswer);
  const notices = message.steps.filter((step) => step.afterAnswer);

  const isThinking =
    message.status === "streaming" && message.segments.length === 0;
  const isBlank = isThinking && timeline.length === 0;

  return (
    <div className="space-y-3">
      {/* What the agent did before it started writing. The last step spins
          until the first segment lands. */}
      {timeline.length > 0 && (
        <ol
          className={cn(
            "space-y-1 text-xs",
            message.status === "streaming"
              ? "text-muted-foreground"
              : "text-muted-foreground/60"
          )}
        >
          {timeline.map((step, index) => {
            const isCurrent = isThinking && index === timeline.length - 1;
            return (
              <li key={index} className="flex items-center gap-2">
                {isCurrent ? (
                  <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
                ) : (
                  <Check className="h-3 w-3 shrink-0" />
                )}
                <span>{step.label}</span>
              </li>
            );
          })}
        </ol>
      )}

      <div className="text-sm leading-relaxed">
        {isBlank ? (
          <span className="flex items-center gap-2 text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
            Thinking…
          </span>
        ) : message.segments.length > 0 ? (
          <p className="whitespace-pre-wrap">
            {message.segments.map((segment, index) => (
              <span key={index}>
                {segment.text}
                {segment.cites.map((cite) => {
                  const source = byNumber.get(cite);
                  const fromLibrary =
                    hadAttachments && source !== undefined && !source.attached;
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
                            )}${fromLibrary ? " · from your library" : ""}`
                          : undefined
                      }
                      className={cn(
                        "mx-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded px-1 align-super text-[10px] font-medium transition-colors disabled:opacity-40",
                        fromLibrary
                          ? "bg-amber-500/15 text-amber-500 hover:bg-amber-500/30"
                          : "bg-primary/15 text-primary hover:bg-primary/30"
                      )}
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
        ) : null}
      </div>

      {/* The agent's own late notices — e.g. giving up at its iteration cap
          after some of the answer had already streamed. */}
      {notices.map((notice, index) => (
        <p
          key={index}
          className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-xs text-amber-500"
        >
          <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
          {notice.label}
        </p>
      ))}

      {discovered.size > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-muted-foreground">Sources</p>

          {/* F4: broadening is allowed, but never silent. */}
          {broadenedToLibrary && (
            <p className="flex items-center gap-1.5 text-xs text-amber-500">
              <TriangleAlert className="h-3 w-3 shrink-0" />
              Not in your attached files — answered from the library.
            </p>
          )}

          <div className="flex flex-wrap gap-2">
            {Array.from(discovered.values()).map((source) => {
              const contentType =
                files.find((file) => file.fileId === source.fileId)
                  ?.contentType ?? "video/";
              const Icon = contentType.startsWith("audio/")
                ? FileAudio
                : FileVideo;
              const isAttached = attachedIds.includes(source.fileId);

              return (
                <div
                  key={source.fileId}
                  className="flex w-56 max-w-full flex-col gap-2 rounded-xl border border-border bg-card p-3"
                >
                  <div className="flex items-center gap-2">
                    <Icon className="h-4 w-4 shrink-0 text-primary" />
                    <span
                      className="min-w-0 flex-1 truncate text-sm font-medium"
                      title={source.fileDisplayName}
                    >
                      {source.fileDisplayName}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => onPlay(source)}
                      className="flex-1"
                    >
                      <Play />
                      Play
                    </Button>
                    <Button
                      size="sm"
                      variant={isAttached ? "secondary" : "outline"}
                      disabled={isAttached || atLimit}
                      onClick={() => onAttach(source)}
                      title={
                        isAttached
                          ? "Already attached"
                          : atLimit
                          ? `Limit reached: ${MAX_ATTACHMENTS} files`
                          : "Attach to your next question"
                      }
                      className="flex-1"
                    >
                      {isAttached ? <Check /> : <Plus />}
                      {isAttached ? "Attached" : "Attach"}
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {message.status === "error" && (
        <p className="flex items-center gap-2 rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2 text-xs text-red-500">
          <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
          {message.error}
        </p>
      )}
    </div>
  );
}
