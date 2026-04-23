"use client";

import { useParams } from "next/navigation";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { MessageSquare, Send, ArrowLeft } from "lucide-react";
import Link from "next/link";

export default function ChatPage() {
  const params = useParams();
  const fileId = params.fileId as string;

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-1 flex-col px-4 py-6">
      {/* Back link */}
      <Link
        href="/dashboard"
        className="mb-4 flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors w-fit"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to files
      </Link>

      {/* Chat area (placeholder) */}
      <Card className="flex flex-1 flex-col items-center justify-center p-10">
        <div className="rounded-xl bg-primary/10 p-4 mb-4">
          <MessageSquare className="h-10 w-10 text-primary" />
        </div>
        <h2 className="text-xl font-semibold mb-2">Chat with your media</h2>
        <p className="text-sm text-muted-foreground text-center max-w-sm mb-2">
          Ask questions about the content of this file. AI will respond using
          the processed transcripts and visual analysis.
        </p>
        <p className="text-xs text-muted-foreground/60 font-mono">
          File ID: {fileId}
        </p>
        <p className="mt-4 text-sm font-medium text-yellow-500">
          🚧 Coming Soon
        </p>
      </Card>

      {/* Input area (placeholder) */}
      <div className="mt-4 flex gap-2">
        <Input
          placeholder="Ask a question about this file..."
          disabled
          className="h-12"
        />
        <Button size="lg" disabled className="gap-2 px-6">
          <Send className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
