"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { listConversations } from "@/lib/api";
import type { ConversationSummary } from "@/lib/types";

interface ConversationsContextValue {
  conversations: ConversationSummary[];
  isLoading: boolean;
  /** Re-fetches the list. Called after a send, when a title lands or the
   *  ordering changes. */
  refresh: () => void;
  /** Drops a row locally after a successful delete, so the sidebar doesn't
   *  wait on a round trip to stop showing something that is gone. */
  remove: (id: string) => void;
  /** The conversation on screen. Set by ChatPanel rather than derived from
   *  usePathname(): the first send rewrites the URL with history.replaceState,
   *  which the router does not observe, so state is the reliable source. */
  activeId: string | null;
  setActiveId: (id: string | null) => void;
}

const ConversationsContext = createContext<ConversationsContextValue | null>(
  null
);

/**
 * Nullable on purpose — ChatPanel also renders outside this provider, and a
 * missing sidebar is not an error there.
 */
export function useConversations(): ConversationsContextValue | null {
  return useContext(ConversationsContext);
}

export function ConversationsProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [activeId, setActiveId] = useState<string | null>(null);

  const refresh = useCallback(() => {
    void (async () => {
      try {
        setConversations(await listConversations());
      } catch {
        // A failed list leaves the previous rows up; the chat still works.
      } finally {
        setIsLoading(false);
      }
    })();
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const remove = useCallback((id: string) => {
    setConversations((current) => current.filter((item) => item.id !== id));
  }, []);

  const value = useMemo<ConversationsContextValue>(
    () => ({
      conversations,
      isLoading,
      refresh,
      remove,
      activeId,
      setActiveId,
    }),
    [conversations, isLoading, refresh, remove, activeId]
  );

  return (
    <ConversationsContext.Provider value={value}>
      {children}
    </ConversationsContext.Provider>
  );
}
