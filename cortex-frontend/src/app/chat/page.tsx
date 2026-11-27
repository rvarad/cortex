import { ChatPanel } from "@/components/chat/chat-panel";

/**
 * A fresh chat. Nothing is created until the first question is sent.
 *
 * `?attach={fileId}` pre-attaches a file — the entry point from a file tile.
 * In Next 16 `searchParams` is a promise on a server page.
 */
export default async function NewChatPage({
  searchParams,
}: {
  searchParams: Promise<{ attach?: string | string[] }>;
}) {
  const { attach } = await searchParams;
  const fileId = Array.isArray(attach) ? attach[0] : attach;

  return <ChatPanel fileId={fileId} />;
}
