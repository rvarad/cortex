-- Adding more columns to file_metadata. These columns are populated in media processing service, during chunking.

ALTER TABLE file_metadata
  ADD COLUMN duration_seconds DOUBLE PRECISION,
  ADD COLUMN has_video BOOLEAN,
  ADD COLUMN has_audio BOOLEAN,
  ADD COLUMN video_codec VARCHAR(255);