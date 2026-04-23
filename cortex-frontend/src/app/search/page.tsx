"use client";

import { useState } from "react";
import { search } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Search as SearchIcon,
  Loader2,
  FileVideo,
  Clock,
  Languages,
  Sparkles,
} from "lucide-react";
import { formatDuration } from "@/lib/helpers";
import { motion, AnimatePresence } from "framer-motion";

interface SearchResult {
  id: string;
  fileId: string;
  fileDisplayName: string;
  chunkIndex: number;
  startTime: number;
  endTime: number;
  transcript: string;
  visualSummary: string;
  languageCode: string;
  score: number;
}

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    setIsSearching(true);
    setHasSearched(true);
    try {
      const data = await search({ query: query.trim() });
      setResults(data);
    } catch (error) {
      console.error("Search failed:", error);
      setResults([]);
    } finally {
      setIsSearching(false);
    }
  };

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Search</h1>
        <p className="mt-1 text-muted-foreground">
          Search across all your processed media using AI
        </p>
      </div>

      {/* Search Bar */}
      <form onSubmit={handleSearch} className="flex gap-3">
        <div className="relative flex-1">
          <SearchIcon className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search your media library..."
            className="pl-10 h-12 text-base"
            id="search-input"
          />
        </div>
        <Button
          type="submit"
          size="lg"
          disabled={isSearching || !query.trim()}
          className="gap-2 px-6"
          id="search-button"
        >
          {isSearching ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <SearchIcon className="h-4 w-4" />
          )}
          Search
        </Button>
      </form>

      {/* Results */}
      {isSearching ? (
        <div className="flex flex-col items-center py-16">
          <Loader2 className="mb-4 h-8 w-8 animate-spin text-primary" />
          <p className="text-sm text-muted-foreground">
            Searching across your media...
          </p>
        </div>
      ) : hasSearched && results.length === 0 ? (
        <div className="flex flex-col items-center py-16">
          <SearchIcon className="mb-4 h-12 w-12 text-muted-foreground/30" />
          <p className="text-lg font-medium text-muted-foreground">
            No results found
          </p>
          <p className="mt-1 text-sm text-muted-foreground/70">
            Try a different search query
          </p>
        </div>
      ) : (
        <AnimatePresence>
          <div className="space-y-4">
            {results.length > 0 && (
              <p className="text-sm text-muted-foreground">
                {results.length} result{results.length !== 1 ? "s" : ""} found
              </p>
            )}
            {results.map((result, index) => (
              <motion.div
                key={result.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: index * 0.05 }}
              >
                <Card className="p-5 transition-all hover:shadow-md hover:border-primary/20">
                  {/* Result Header */}
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <FileVideo className="h-4 w-4 text-primary" />
                      <span className="text-sm font-medium">
                        {result.fileDisplayName}
                      </span>
                      <Badge variant="outline" className="text-xs">
                        Chunk {result.chunkIndex}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {formatDuration(result.startTime)} —{" "}
                        {formatDuration(result.endTime)}
                      </span>
                      {result.languageCode && (
                        <span className="flex items-center gap-1">
                          <Languages className="h-3 w-3" />
                          {result.languageCode}
                        </span>
                      )}
                      <Badge
                        variant="secondary"
                        className="text-xs font-mono"
                      >
                        {(result.score * 100).toFixed(0)}%
                      </Badge>
                    </div>
                  </div>

                  <Separator className="mb-3" />

                  {/* Transcript */}
                  {result.transcript && (
                    <div className="mb-3">
                      <p className="text-xs font-medium text-muted-foreground mb-1">
                        Transcript
                      </p>
                      <p className="text-sm leading-relaxed">
                        {result.transcript}
                      </p>
                    </div>
                  )}

                  {/* Visual Summary */}
                  {result.visualSummary && (
                    <div>
                      <p className="flex items-center gap-1 text-xs font-medium text-muted-foreground mb-1">
                        <Sparkles className="h-3 w-3" />
                        Visual Summary
                      </p>
                      <p className="text-sm leading-relaxed text-muted-foreground">
                        {result.visualSummary}
                      </p>
                    </div>
                  )}
                </Card>
              </motion.div>
            ))}
          </div>
        </AnimatePresence>
      )}
    </div>
  );
}
