"use client";

import { useParams } from "next/navigation";
import { ChatPanel } from "@/components/chat/chat-panel";

/** Per-file chat: the same panel as the library chat, with this file attached. */
export default function FileChatPage() {
  const params = useParams();
  const fileId = params.fileId as string;

  return <ChatPanel fileId={fileId} />;
}