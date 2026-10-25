import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { AuthProvider } from "@/providers/auth-provider";
import { MediaPlayerProvider } from "@/components/media/media-player-provider";
import { Toaster } from "@/components/ui/sonner";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: "Cortex — AI Media Intelligence",
  description:
    "Upload, process, and search through your media files with AI-powered intelligence.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className={`${inter.variable} font-sans antialiased`}>
        <AuthProvider>
          {/* Mounted at the root so the dock survives navigation between the
              dashboard and a file's chat page, and both can drive it. */}
          <MediaPlayerProvider>
            {/* Before the page: React runs sibling effects in order, and the Toaster only
                shows toasts sent after its effect subscribes. After the page, a toast sent
                on first load (the "Sign-in failed" one) was lost. */}
            <Toaster richColors position="bottom-right" />
            {children}
          </MediaPlayerProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
