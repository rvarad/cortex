"use client";

import { useAuth } from "@/providers/auth-provider";
import { getLogoutUrl } from "@/lib/api";
import { Brain, LogOut, MessageSquare, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import Link from "next/link";

export function Navbar() {
  const { user } = useAuth();

  const handleLogout = () => {
    window.location.href = getLogoutUrl();
  };

  return (
    <nav className="sticky top-0 z-50 border-b border-border/50 bg-background/80 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
        {/* Left — Logo */}
        <Link href="/dashboard" className="flex items-center gap-2.5">
          <div className="rounded-lg bg-primary/10 p-1.5">
            <Brain className="h-5 w-5 text-primary" />
          </div>
          <span className="text-lg font-semibold tracking-tight">
            Cortex
          </span>
        </Link>

        {/* Center — Nav Links */}
        <div className="hidden items-center gap-1 md:flex">
          <Button
            variant="ghost"
            size="sm"
            nativeButton={false}
            render={<Link href="/dashboard" />}
          >
            Files
          </Button>
          <Button
            variant="ghost"
            size="sm"
            nativeButton={false}
            render={<Link href="/chat" />}
          >
            <MessageSquare className="mr-1.5 h-4 w-4" />
            Chat
          </Button>
          <Button
            variant="ghost"
            size="sm"
            nativeButton={false}
            render={<Link href="/search" />}
          >
            <Search className="mr-1.5 h-4 w-4" />
            Search
          </Button>
        </div>

        {/* Right — User Menu */}
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                className="relative h-9 w-9 rounded-full overflow-hidden"
                id="user-menu-button"
              />
            }
          >
            <Avatar className="h-9 w-9">
              <AvatarImage src={user?.picture} alt={user?.name} />
              <AvatarFallback>
                {user?.name
                  ?.split(" ")
                  .map((n) => n[0])
                  .join("")
                  .toUpperCase() || "U"}
              </AvatarFallback>
            </Avatar>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <div className="flex items-center gap-2 p-2">
              <Avatar className="h-8 w-8">
                <AvatarImage src={user?.picture} alt={user?.name} />
                <AvatarFallback>U</AvatarFallback>
              </Avatar>
              <div className="flex flex-col">
                <p className="text-sm font-medium">{user?.name}</p>
                <p className="text-xs text-muted-foreground">{user?.email}</p>
              </div>
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="text-red-400">
              <LogOut className="mr-2 h-4 w-4" />
              Log out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </nav>
  );
}
