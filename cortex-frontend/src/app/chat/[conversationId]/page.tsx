"use client";

import { useParams } from "next/navigation";
import { ChatPanel } from "@/components/chat/chat-panel";

/** An existing chat, loaded from the server and continued. */
export default function ConversationPage() {
  const params = useParams();
  const conversationId = params.conversationId as string;

  return <ChatPanel conversationId={conversationId} />;
}
