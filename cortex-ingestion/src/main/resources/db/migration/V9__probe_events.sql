-- Add PROBE_STARTED / PROBE_COMPLETE so the manifest (hasVideo/hasAudio/codec/
-- duration) can reach the frontend right after ffprobe, letting the UI render
-- only the skeletons that apply (transcript for audio, visual-desc for video).
-- Also folds in PIPELINE_FAILED, which was added to the enum but never made it
-- into the CHECK constraint.

ALTER TABLE pipeline_events DROP CONSTRAINT IF EXISTS pipeline_events_event_type_check;

ALTER TABLE pipeline_events
    ADD CONSTRAINT pipeline_events_event_type_check
        CHECK (event_type IN (
            'PIPELINE_STARTED','PROBE_STARTED','PROBE_COMPLETE','CHUNKING_STARTED','CHUNKING_COMPLETE',
            'CHUNK_UPLOAD_STARTED','CHUNK_UPLOAD_COMPLETE','MEDIA_CHUNK_READY','VISION_ANALYSIS_STARTED',
            'VISION_ANALYSIS_COMPLETE','TRANSCRIPTION_STARTED','TRANSCRIPTION_COMPLETE','EMBEDDING_COMPLETE',
            'PIPELINE_COMPLETE','PIPELINE_FAILED','UPLOAD_REJECTED','NORMALISATION_STARTED',
            'NORMALISATION_COMPLETE','NORMALISATION_FAILED'
        ));
