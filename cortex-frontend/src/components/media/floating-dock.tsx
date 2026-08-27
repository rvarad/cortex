"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { ChevronDown, GripVertical, Pin, PinOff, X } from "lucide-react";

const MIN_WIDTH = 260;
const MAX_WIDTH = 720;
const DEFAULT_WIDTH = 380;
const ATTACHED_WIDTH = 320;
const EDGE_GAP = 16;

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

interface FloatingDockProps {
  title: string;
  collapsed: boolean;
  onCollapsedChange: (collapsed: boolean) => void;
  /** Attached = pinned bottom-left at a fixed size, neither draggable nor
   *  resizable. Detaching hands the panel back its free position and size. */
  attached: boolean;
  onAttachedChange: (attached: boolean) => void;
  onClose: () => void;
  /** Kept mounted while collapsed — hidden with CSS, never unmounted, so media
   *  keeps playing and imperative seeks still land on a live element. */
  children: React.ReactNode;
}

/**
 * A floating window: draggable by its title bar, resizable from the footer
 * grip, collapsible to a title-only pill, or attached to the bottom-left
 * corner. Deliberately knows nothing about what it contains.
 */
export function FloatingDock({
  title,
  collapsed,
  onCollapsedChange,
  attached,
  onAttachedChange,
  onClose,
  children,
}: FloatingDockProps) {
  const panelRef = useRef<HTMLDivElement>(null);

  // null means "not moved yet" and the panel sits at its default bottom-left
  // anchor. The first drag or resize pins it to explicit coordinates.
  const [position, setPosition] = useState<{ x: number; y: number } | null>(
    null
  );
  const [width, setWidth] = useState(DEFAULT_WIDTH);

  const dragOffset = useRef<{ x: number; y: number } | null>(null);
  const resizeStart = useRef<{ pointerX: number; width: number } | null>(null);

  const pinPosition = useCallback(() => {
    setPosition((current) => {
      if (current) return current;
      const rect = panelRef.current?.getBoundingClientRect();
      return rect ? { x: rect.left, y: rect.top } : current;
    });
  }, []);

  // Keep the panel reachable when the window shrinks under it.
  useEffect(() => {
    function handleResize() {
      const rect = panelRef.current?.getBoundingClientRect();

      setWidth((current) =>
        clamp(
          current,
          MIN_WIDTH,
          Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, window.innerWidth - EDGE_GAP * 2))
        )
      );

      setPosition((current) => {
        if (!current || !rect) return current;
        return {
          x: clamp(current.x, 0, Math.max(0, window.innerWidth - rect.width)),
          y: clamp(current.y, 0, Math.max(0, window.innerHeight - rect.height)),
        };
      });
    }

    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  /** Detaching leaves the panel exactly where it sits and hands control back,
   *  rather than snapping it to a corner the user didn't ask for. */
  function handleAttachToggle() {
    if (!attached) {
      onAttachedChange(true);
      return;
    }

    const rect = panelRef.current?.getBoundingClientRect();
    if (rect) {
      setPosition({ x: rect.left, y: rect.top });
      setWidth(clamp(rect.width, MIN_WIDTH, MAX_WIDTH));
    }
    onAttachedChange(false);
  }

  function handleDragStart(event: React.PointerEvent<HTMLDivElement>) {
    if (attached) return;
    // The title bar also carries the collapse, attach and close buttons.
    if ((event.target as HTMLElement).closest("button")) return;

    const rect = panelRef.current?.getBoundingClientRect();
    if (!rect) return;

    pinPosition();
    dragOffset.current = {
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function handleDragMove(event: React.PointerEvent<HTMLDivElement>) {
    const offset = dragOffset.current;
    if (!offset) return;

    const rect = panelRef.current?.getBoundingClientRect();
    const panelWidth = rect?.width ?? width;
    const panelHeight = rect?.height ?? 0;

    setPosition({
      x: clamp(
        event.clientX - offset.x,
        0,
        Math.max(0, window.innerWidth - panelWidth)
      ),
      y: clamp(
        event.clientY - offset.y,
        0,
        Math.max(0, window.innerHeight - panelHeight)
      ),
    });
  }

  function handleDragEnd(event: React.PointerEvent<HTMLDivElement>) {
    dragOffset.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  function handleResizeStart(event: React.PointerEvent<HTMLDivElement>) {
    pinPosition();
    resizeStart.current = { pointerX: event.clientX, width };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function handleResizeMove(event: React.PointerEvent<HTMLDivElement>) {
    const start = resizeStart.current;
    if (!start) return;

    const left = position?.x ?? 0;
    const available = position
      ? window.innerWidth - left - EDGE_GAP
      : window.innerWidth - EDGE_GAP * 2;

    setWidth(
      clamp(
        start.width + (event.clientX - start.pointerX),
        MIN_WIDTH,
        Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, available))
      )
    );
  }

  function handleResizeEnd(event: React.PointerEvent<HTMLDivElement>) {
    resizeStart.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  return (
    <div
      ref={panelRef}
      style={
        attached
          ? { left: EDGE_GAP, bottom: EDGE_GAP, width: ATTACHED_WIDTH }
          : position
          ? { left: position.x, top: position.y, width }
          : { left: EDGE_GAP, bottom: EDGE_GAP, width }
      }
      className="fixed z-50 overflow-hidden rounded-xl border border-border bg-card shadow-2xl shadow-black/40"
    >
      <div
        onPointerDown={handleDragStart}
        onPointerMove={handleDragMove}
        onPointerUp={handleDragEnd}
        onPointerCancel={handleDragEnd}
        className={cn(
          "flex touch-none items-center gap-1.5 border-b border-border/60 bg-muted/40 px-2 py-1.5",
          attached ? "cursor-default" : "cursor-grab active:cursor-grabbing"
        )}
      >
        <GripVertical
          className={cn(
            "h-4 w-4 shrink-0 text-muted-foreground/70",
            attached && "opacity-30"
          )}
        />
        <span
          className="min-w-0 flex-1 truncate text-xs font-medium"
          title={title}
        >
          {title}
        </span>
        <button
          type="button"
          onClick={handleAttachToggle}
          aria-label={attached ? "Detach player" : "Attach player to corner"}
          title={attached ? "Detach player" : "Attach player to corner"}
          className={cn(
            "rounded p-1 transition-colors hover:bg-muted hover:text-foreground",
            attached ? "text-primary" : "text-muted-foreground"
          )}
        >
          {attached ? (
            <PinOff className="h-3.5 w-3.5" />
          ) : (
            <Pin className="h-3.5 w-3.5" />
          )}
        </button>
        <button
          type="button"
          onClick={() => onCollapsedChange(!collapsed)}
          aria-label={collapsed ? "Expand player" : "Collapse player"}
          className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <ChevronDown
            className={cn(
              "h-3.5 w-3.5 transition-transform",
              collapsed && "rotate-180"
            )}
          />
        </button>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close player"
          className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      <div className={cn(collapsed && "hidden")}>{children}</div>

      {/* Resize grip lives in its own strip rather than over the media — the
          bottom-right of a <video> is where the native fullscreen button sits.
          An attached dock is a fixed size, so it has no grip at all. */}
      {!collapsed && !attached && (
        <div
          onPointerDown={handleResizeStart}
          onPointerMove={handleResizeMove}
          onPointerUp={handleResizeEnd}
          onPointerCancel={handleResizeEnd}
          className="flex h-4 cursor-se-resize touch-none items-center justify-end bg-muted/40 pr-1"
        >
          <div className="pointer-events-none mr-0.5 mb-0.5 h-2 w-2 border-r-2 border-b-2 border-muted-foreground/40" />
        </div>
      )}
    </div>
  );
}