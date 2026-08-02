-- Extend file_status: REJECTED and FAILED
-- Extend event_type: UPLOAD_REJECTED

ALTER TABLE file_metadata
    DROP CONSTRAINT IF EXISTS file_metadata_file_status_check;

ALTER TABLE file_metadata
    ADD CONSTRAINT file_metadata_file_status_check
        CHECK (file_status IN ('PENDING','UPLOADED','CHUNKED','PROCESSING','COMPLETED','REJECTED','FAILED'));

ALTER TABLE pipeline_events DROP CONSTRAINT IF EXISTS pipeline_events_event_type_check;

ALTER TABLE pipeline_events
    ADD CONSTRAINT pipeline_events_event_type_check
        CHECK (event_type IN (
            'PIPELINE_STARTED','CHUNKING_STARTED','CHUNKING_COMPLETE','CHUNK_UPLOAD_STARTED',
            'CHUNK_UPLOAD_COMPLETE','MEDIA_CHUNK_READY','VISION_ANALYSIS_STARTED','VISION_ANALYSIS_COMPLETE',
            'TRANSCRIPTION_STARTED','TRANSCRIPTION_COMPLETE','EMBEDDING_COMPLETE','PIPELINE_COMPLETE',
            'UPLOAD_REJECTED'
        ));
