import { AuthGuard } from "@/components/auth/auth-guard";
import { Navbar } from "@/components/layout/navbar";
import { ConversationsProvider } from "@/components/chat/conversations-provider";
import { ConversationSidebar } from "@/components/chat/conversation-sidebar";

export default function ChatLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthGuard>
      <ConversationsProvider>
        <div className="flex h-screen flex-col bg-background">
          <Navbar />
          <div className="flex min-h-0 flex-1">
            <ConversationSidebar />
            <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
              {children}
            </main>
          </div>
        </div>
      </ConversationsProvider>
    </AuthGuard>
  );
}
