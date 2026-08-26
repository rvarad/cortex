

ALTER TABLE file_metadata
  ADD COLUMN rejection_reason varchar(255)
    CHECK (rejection_reason IN ('TOO_BIG', 'TOO_LONG', 'CORRUPTED'));