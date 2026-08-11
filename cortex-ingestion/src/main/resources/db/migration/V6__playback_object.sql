-- Normalised playback copy (playback/<fileId>.mp4)
-- Nullable: column filled later during processing step

ALTER TABLE file_metadata
  ADD COLUMN playback_object_name varchar(255);