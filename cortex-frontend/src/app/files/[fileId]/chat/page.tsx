"use client";

import { Card } from "@/components/ui/card";
import { MessageSquare, ArrowLeft } from "lucide-react";
import Link from "next/link";

// The chat UI is built but not finished, so it stays out of the merged branch.
// Restore by uncommenting these two lines and deleting the placeholder below.
// import { useParams } from "next/navigation";
// import { ChatPanel } from "@/components/chat/chat-panel";

/** Per-file chat: the same panel as the library chat, with this file attached. */
export default function FileChatPage() {
  // const params = useParams();
  // const fileId = params.fileId as string;
  // return <ChatPanel fileId={fileId} />;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col px-4 py-6">
      <Link
        href="/dashboard"
        className="mb-4 flex w-fit items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to files
      </Link>

      <Card className="flex flex-1 flex-col items-center justify-center p-10">
        <div className="mb-4 rounded-xl bg-primary/10 p-4">
          <MessageSquare className="h-10 w-10 text-primary" />
        </div>
        <h2 className="mb-2 text-xl font-semibold">Chat with your media</h2>
        <p className="max-w-sm text-center text-sm text-muted-foreground">
          Ask questions about this file and get answers with citations that jump
          to the exact moment.
        </p>
        <p className="mt-4 text-sm font-medium text-yellow-500">
          🚧 Under progress
        </p>
      </Card>
    </div>
  );
}