import { ChatPanel } from "@/components/chat/chat-panel";

/**
 * Library-wide chat: the same panel with nothing attached. The backend already
 * treats a null fileId as "retrieve across everything this user owns".
 */
export default function LibraryChatPage() {
  return <ChatPanel />;
}