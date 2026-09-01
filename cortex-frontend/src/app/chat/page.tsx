import { Card } from "@/components/ui/card";
import { MessageSquare } from "lucide-react";

// The chat UI is built but not finished, so it stays out of the merged branch.
// Restore by uncommenting the import and the return below, then deleting the
// placeholder.
// import { ChatPanel } from "@/components/chat/chat-panel";

/**
 * Library-wide chat: the same panel with nothing attached. The backend already
 * treats a null fileId as "retrieve across everything this user owns".
 */
export default function LibraryChatPage() {
  // return <ChatPanel />;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col px-4 py-6">
      <Card className="flex flex-1 flex-col items-center justify-center p-10">
        <div className="mb-4 rounded-xl bg-primary/10 p-4">
          <MessageSquare className="h-10 w-10 text-primary" />
        </div>
        <h2 className="mb-2 text-xl font-semibold">Chat with your library</h2>
        <p className="max-w-sm text-center text-sm text-muted-foreground">
          Ask questions across everything you&apos;ve uploaded and get answers
          with citations back to the source.
        </p>
        <p className="mt-4 text-sm font-medium text-yellow-500">
          🚧 Under progress
        </p>
      </Card>
    </div>
  );
}