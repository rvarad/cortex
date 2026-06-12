-- V1__init.sql
-- Baseline schema for Cortex (cleaned from pg_dump --schema-only).
-- pgvector extension, get_pg_dictionary(), and the four core tables.
-- Indexes + unique(file_id, chunk_index) live in V2.

CREATE EXTENSION IF NOT EXISTS vector;

-- Whisper language code -> Postgres text-search dictionary (regconfig).
-- Used by MediaChunkRepository.lexicalSearch. IMMUTABLE so it's index-usable.
CREATE FUNCTION get_pg_dictionary(lang_code text) RETURNS regconfig
    LANGUAGE plpgsql IMMUTABLE
    AS $$
BEGIN
    RETURN CASE lang_code
        WHEN 'ar' THEN 'arabic'::regconfig
        WHEN 'hy' THEN 'armenian'::regconfig
        WHEN 'eu' THEN 'basque'::regconfig
        WHEN 'ca' THEN 'catalan'::regconfig
        WHEN 'da' THEN 'danish'::regconfig
        WHEN 'nl' THEN 'dutch'::regconfig
        WHEN 'en' THEN 'english'::regconfig
        WHEN 'fi' THEN 'finnish'::regconfig
        WHEN 'fr' THEN 'french'::regconfig
        WHEN 'de' THEN 'german'::regconfig
        WHEN 'el' THEN 'greek'::regconfig
        WHEN 'hi' THEN 'hindi'::regconfig
        WHEN 'hu' THEN 'hungarian'::regconfig
        WHEN 'id' THEN 'indonesian'::regconfig
        WHEN 'ga' THEN 'irish'::regconfig
        WHEN 'it' THEN 'italian'::regconfig
        WHEN 'lt' THEN 'lithuanian'::regconfig
        WHEN 'ne' THEN 'nepali'::regconfig
        WHEN 'nb' THEN 'norwegian'::regconfig
        WHEN 'nn' THEN 'norwegian'::regconfig
        WHEN 'pt' THEN 'portuguese'::regconfig
        WHEN 'ro' THEN 'romanian'::regconfig
        WHEN 'ru' THEN 'russian'::regconfig
        WHEN 'sr' THEN 'serbian'::regconfig
        WHEN 'es' THEN 'spanish'::regconfig
        WHEN 'sv' THEN 'swedish'::regconfig
        WHEN 'ta' THEN 'tamil'::regconfig
        WHEN 'tr' THEN 'turkish'::regconfig
        WHEN 'yi' THEN 'yiddish'::regconfig
        ELSE 'simple'::regconfig
    END;
END;
$$;

CREATE TABLE users (
    id            varchar(255)                   NOT NULL,
    created_at    timestamp(6) without time zone NOT NULL,
    email         varchar(255)                   NOT NULL,
    last_login_at timestamp(6) without time zone NOT NULL,
    name          varchar(255)                   NOT NULL,
    picture_url   varchar(255),
    CONSTRAINT users_pkey     PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE file_metadata (
    id                uuid                           NOT NULL,
    bucket_name       varchar(255)                   NOT NULL,
    content_type      varchar(255)                   NOT NULL,
    created_at        timestamp(6) without time zone NOT NULL,
    file_display_name varchar(255)                   NOT NULL,
    file_size         bigint                         NOT NULL,
    file_status       varchar(255)                   NOT NULL,
    object_name       varchar(255)                   NOT NULL,
    total_chunks      integer,
    user_id           varchar(255)                   NOT NULL,
    CONSTRAINT file_metadata_pkey PRIMARY KEY (id),
    CONSTRAINT file_metadata_file_status_check
        CHECK (file_status IN ('PENDING','UPLOADED','CHUNKED','PROCESSING','COMPLETED'))
);

CREATE TABLE media_chunk (
    id             uuid             NOT NULL,
    chunk_index    integer          NOT NULL,
    embedding      vector(768),
    end_time       double precision NOT NULL,
    file_id        uuid             NOT NULL,
    language_code  varchar(255),
    start_time     double precision NOT NULL,
    status         varchar(255),
    transcript     text,
    user_id        varchar(255)     NOT NULL,
    visual_summary text,
    CONSTRAINT media_chunk_pkey PRIMARY KEY (id),
    CONSTRAINT media_chunk_status_check
        CHECK (status IN ('UPLOADED','IN_PROGRESS','COMPLETED','FAILED'))
);

CREATE TABLE pipeline_events (
    id          uuid                           NOT NULL,
    chunk_id    uuid,
    chunk_index integer,
    created_at  timestamp(6) without time zone NOT NULL,
    event_type  varchar(255)                   NOT NULL,
    file_id     uuid                           NOT NULL,
    message     varchar(255)                   NOT NULL,
    metadata    jsonb                          NOT NULL,
    CONSTRAINT pipeline_events_pkey PRIMARY KEY (id),
    CONSTRAINT pipeline_events_event_type_check
        CHECK (event_type IN (
            'PIPELINE_STARTED','CHUNKING_STARTED','CHUNKING_COMPLETE','CHUNK_UPLOAD_STARTED',
            'CHUNK_UPLOAD_COMPLETE','MEDIA_CHUNK_READY','VISION_ANALYSIS_STARTED','VISION_ANALYSIS_COMPLETE',
            'TRANSCRIPTION_STARTED','TRANSCRIPTION_COMPLETE','EMBEDDING_COMPLETE','PIPELINE_COMPLETE'
        ))
);