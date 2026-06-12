-- HNSW index for cosine vector search + the (file_id, chunk_index) uniqueness
-- guard (which also serves all file_id lookups via leftmost-prefix).

-- Idempotency + lookup index. Combination of file_id and chunk_index is unique, so no index needed
ALTER TABLE media_chunk
  ADD CONSTRAINT uq_media_chunk_file_chunk UNIQUE (file_id, chunk_index);

-- Approximate-nearest-neighbor index for the `embedding <=> :vec` cosine search.
CREATE INDEX IF NOT EXISTS idx_media_chunk_embedding_hnsw
  ON media_chunk
  USING hnsw (embedding vector_cosine_ops);