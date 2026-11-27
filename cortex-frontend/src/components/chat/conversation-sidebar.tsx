"use client";

import { useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { deleteConversation } from "@/lib/api";
import type { ConversationSummary } from "@/lib/types";
import { useConversations } from "./conversations-provider";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import {
  AlertTriangle,
  Loader2,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Trash2,
} from "lucide-react";

const COLLAPSED_KEY = "cortex.chat.sidebar.collapsed";

/**
 * localStorage as an external store rather than an effect that setStates on
 * mount: the server and the first client render both see false, then
 * useSyncExternalStore swaps in the stored value — no hydration mismatch and
 * no flash of the wrong width.
 */
const collapsedStore = {
  listeners: new Set<() => void>(),

  subscribe(listener: () => void) {
    collapsedStore.listeners.add(listener);
    return () => {
      collapsedStore.listeners.delete(listener);
    };
  },

  get(): boolean {
    try {
      return window.localStorage.getItem(COLLAPSED_KEY) === "true";
    } catch {
      // Private mode or blocked storage — expanded is the default.
      return false;
    }
  },

  set(next: boolean) {
    try {
      window.localStorage.setItem(COLLAPSED_KEY, String(next));
    } catch {
      // Not worth failing the interaction over; the toggle still applies.
    }
    // storage events do not fire in the tab that wrote, so notify directly.
    collapsedStore.listeners.forEach((listener) => listener());
  },
};

export function ConversationSidebar() {
  const router = useRouter();
  const context = useConversations();

  const collapsed = useSyncExternalStore(
    collapsedStore.subscribe,
    collapsedStore.get,
    () => false
  );
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [pendingDelete, setPendingDelete] =
    useState<ConversationSummary | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  function toggleCollapsed() {
    collapsedStore.set(!collapsed);
  }

  if (!context) return null;

  const { conversations, isLoading, remove, activeId } = context;

  async function confirmDelete() {
    if (!pendingDelete) return;

    setIsDeleting(true);
    try {
      await deleteConversation(pendingDelete.id);
      remove(pendingDelete.id);

      // Don't leave the user looking at a chat that no longer exists.
      if (activeId === pendingDelete.id) router.push("/chat");

      setPendingDelete(null);
    } catch {
      toast.error("Couldn't delete that chat. Please try again.");
    } finally {
      setIsDeleting(false);
    }
  }

  const panel = (
    <div
      className={cn(
        "flex h-full flex-col border-r border-border/50 bg-card/30 transition-[width]",
        collapsed ? "w-14" : "w-64"
      )}
    >
      <div
        className={cn(
          "flex items-center gap-1 p-2",
          collapsed && "flex-col-reverse"
        )}
      >
        <Button
          variant="outline"
          size={collapsed ? "icon-sm" : "sm"}
          nativeButton={false}
          render={<Link href="/chat" />}
          onClick={() => setDrawerOpen(false)}
          className={cn(!collapsed && "flex-1 justify-start")}
          aria-label="New chat"
          title="New chat"
        >
          <Plus />
          {!collapsed && "New chat"}
        </Button>

        <Button
          variant="ghost"
          size="icon-sm"
          onClick={toggleCollapsed}
          className="hidden md:inline-flex"
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
        </Button>
      </div>

      {!collapsed && (
        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          {isLoading ? (
            <p className="flex items-center gap-2 px-2 py-3 text-xs text-muted-foreground">
              <Loader2 className="h-3 w-3 animate-spin" />
              Loading chats…
            </p>
          ) : conversations.length === 0 ? (
            <p className="px-2 py-3 text-xs text-muted-foreground">
              No chats yet. Ask something to start one.
            </p>
          ) : (
            <ul className="space-y-0.5">
              {conversations.map((conversation) => (
                <li key={conversation.id} className="group/row relative">
                  <Link
                    href={`/chat/${conversation.id}`}
                    onClick={() => setDrawerOpen(false)}
                    title={conversation.title}
                    className={cn(
                      "flex items-center gap-2 rounded-lg py-1.5 pr-8 pl-2 text-sm transition-colors",
                      conversation.id === activeId
                        ? "bg-muted text-foreground"
                        : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"
                    )}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0" />
                    <span className="truncate">{conversation.title}</span>
                  </Link>

                  <button
                    type="button"
                    onClick={() => setPendingDelete(conversation)}
                    aria-label={`Delete ${conversation.title}`}
                    className="absolute top-1/2 right-1 -translate-y-1/2 rounded p-1 text-muted-foreground opacity-0 transition-opacity group-hover/row:opacity-100 focus-visible:opacity-100 hover:text-red-500"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );

  return (
    <>
      {/* Desktop: a real column in the flow. */}
      <div className="hidden md:block">{panel}</div>

      {/* Narrow: a button in the flow, the panel over the chat. */}
      <div className="md:hidden">
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={() => setDrawerOpen(true)}
          aria-label="Open chats"
          className="m-2"
        >
          <PanelLeftOpen />
        </Button>
      </div>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <button
            type="button"
            aria-label="Close chats"
            onClick={() => setDrawerOpen(false)}
            className="absolute inset-0 bg-background/80 backdrop-blur-sm"
          />
          <div className="absolute inset-y-0 left-0 bg-card shadow-xl">
            {panel}
          </div>
        </div>
      )}

      <Dialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-red-500" />
              Delete chat
            </DialogTitle>
            <DialogDescription>
              Delete{" "}
              <span className="font-medium text-foreground">
                {pendingDelete?.title}
              </span>
              ? Every message in it is removed permanently. This cannot be
              undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setPendingDelete(null)}
              disabled={isDeleting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => void confirmDelete()}
              disabled={isDeleting}
            >
              {isDeleting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
