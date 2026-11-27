-- The sweep's "last activity" (the newest event of each file), and the file page's history read.
CREATE INDEX idx_pipeline_events_file_created ON pipeline_events (file_id, created_at);
