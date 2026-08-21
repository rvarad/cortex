-- Normalization support:
--   1. Extend pipeline_events.event_type with the NORMALIZATION_* events.
--   2. Add file_metadata.playback_status so the UI can distinguish "still
--      normalizing" (PENDING) from "normalization failed, no playback version
--      will exist" (UNAVAILABLE). Previously playback_object_name IS NULL
--      conflated those two.

ALTER TABLE pipeline_events DROP CONSTRAINT IF EXISTS pipeline_events_event_type_check;

ALTER TABLE pipeline_events
    ADD CONSTRAINT pipeline_events_event_type_check
        CHECK (event_type IN (
            'PIPELINE_STARTED','CHUNKING_STARTED','CHUNKING_COMPLETE','CHUNK_UPLOAD_STARTED',
            'CHUNK_UPLOAD_COMPLETE','MEDIA_CHUNK_READY','VISION_ANALYSIS_STARTED','VISION_ANALYSIS_COMPLETE',
            'TRANSCRIPTION_STARTED','TRANSCRIPTION_COMPLETE','EMBEDDING_COMPLETE','PIPELINE_COMPLETE',
            'UPLOAD_REJECTED','NORMALISATION_STARTED', 'NORMALISATION_COMPLETE','NORMALISATION_FAILED'
        ));

ALTER TABLE file_metadata
    ADD COLUMN playback_status varchar(255) NOT NULL DEFAULT 'PENDING'
        CHECK (playback_status IN ('PENDING', 'READY', 'UNAVAILABLE'));

-- Existing rows that already have a playback object are actually READY.
UPDATE file_metadata
    SET playback_status = 'READY'
    WHERE playback_object_name IS NOT NULL;
