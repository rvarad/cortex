-- Extend file_status: FOR_CLEANUP (failed, its data not yet removed by the sweep)

ALTER TABLE file_metadata
    DROP CONSTRAINT IF EXISTS file_metadata_file_status_check;

ALTER TABLE file_metadata
    ADD CONSTRAINT file_metadata_file_status_check
        CHECK (file_status IN ('PENDING','UPLOADED','CHUNKED','PROCESSING','COMPLETED','REJECTED','FOR_CLEANUP','FAILED'));
