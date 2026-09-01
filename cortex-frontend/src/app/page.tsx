"use client";

import { useAuth } from "@/providers/auth-provider";
import { getLoginUrl } from "@/lib/api";
import { useRouter, usePathname } from "next/navigation";
import { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Brain, Sparkles, Upload, Search } from "lucide-react";
import { motion } from "framer-motion";

export default function LandingPage() {
  const { isAuthenticated, isLoading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!isLoading && isAuthenticated && pathname !== "/dashboard") {
      router.replace("/dashboard");
    }
  }, [isLoading, isAuthenticated, router, pathname]);

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-background">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  const handleLogin = () => {
    window.location.href = getLoginUrl();
  };

  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-background">
      {/* Background gradient orbs */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -left-40 -top-40 h-80 w-80 rounded-full bg-primary/10 blur-[100px]" />
        <div className="absolute -bottom-40 -right-40 h-80 w-80 rounded-full bg-purple-500/10 blur-[100px]" />
      </div>

      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6 }}
        className="relative z-10 flex flex-col items-center gap-8 px-4 text-center"
      >
        {/* Logo */}
        <div className="flex items-center gap-3">
          <div className="rounded-xl bg-primary/10 p-3 glow-border">
            <Brain className="h-10 w-10 text-primary" />
          </div>
        </div>

        {/* Title */}
        <div className="space-y-3">
          <h1 className="text-5xl font-bold tracking-tight md:text-6xl">
            Cortex
          </h1>
          <p className="max-w-md text-lg text-muted-foreground">
            AI-powered media intelligence. Upload, process, and search your
            media with precision.
          </p>
        </div>

        {/* Feature pills */}
        <div className="flex flex-wrap justify-center gap-3">
          {[
            { icon: Upload, label: "Smart Upload" },
            { icon: Sparkles, label: "AI Processing" },
            { icon: Search, label: "Semantic Search" },
          ].map((feature) => (
            <div
              key={feature.label}
              className="glass-card flex items-center gap-2 rounded-full px-4 py-2 text-sm text-muted-foreground"
            >
              <feature.icon className="h-4 w-4 text-primary" />
              {feature.label}
            </div>
          ))}
        </div>

        {/* Login button */}
        <Button
          size="lg"
          onClick={handleLogin}
          className="mt-4 gap-2 rounded-full px-8 text-base"
          id="login-button"
        >
          <svg className="h-5 w-5" viewBox="0 0 24 24">
            <path
              fill="currentColor"
              d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
            />
            <path
              fill="currentColor"
              d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
            />
            <path
              fill="currentColor"
              d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
            />
            <path
              fill="currentColor"
              d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
            />
          </svg>
          Continue with Google
        </Button>
      </motion.div>
    </div>
  );
}
