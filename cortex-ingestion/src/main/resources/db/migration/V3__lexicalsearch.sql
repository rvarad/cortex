DROP INDEX IF EXISTS idx_media_chunk_fts;

ALTER TABLE media_chunk
  ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(visual_summary, '')) ||
    to_tsvector(get_pg_dictionary(language_code), coalesce(transcript, ''))
  ) STORED;

CREATE INDEX idx_media_chunk_search_vector
  ON media_chunk USING GIN (search_vector);