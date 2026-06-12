--
-- PostgreSQL database dump
--


-- Dumped from database version 15.16 (Debian 15.16-1.pgdg12+1)
-- Dumped by pg_dump version 15.16 (Debian 15.16-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: get_pg_dictionary(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_pg_dictionary(lang_code text) RETURNS regconfig
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


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: file_metadata; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.file_metadata (
    id uuid NOT NULL,
    bucket_name character varying(255) NOT NULL,
    content_type character varying(255) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    file_display_name character varying(255) NOT NULL,
    file_size bigint NOT NULL,
    file_status character varying(255) NOT NULL,
    object_name character varying(255) NOT NULL,
    total_chunks integer,
    user_id character varying(255) NOT NULL,
    CONSTRAINT file_metadata_file_status_check1 CHECK (((file_status)::text = ANY ((ARRAY['PENDING'::character varying, 'UPLOADED'::character varying, 'CHUNKED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying])::text[])))
);


--
-- Name: media_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.media_chunk (
    id uuid NOT NULL,
    chunk_index integer NOT NULL,
    embedding public.vector(768),
    end_time double precision NOT NULL,
    file_id uuid NOT NULL,
    language_code character varying(255),
    start_time double precision NOT NULL,
    status character varying(255),
    transcript text,
    user_id character varying(255) NOT NULL,
    visual_summary text,
    CONSTRAINT media_chunk_status_check CHECK (((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: pipeline_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pipeline_events (
    id uuid NOT NULL,
    chunk_id uuid,
    chunk_index integer,
    created_at timestamp(6) without time zone NOT NULL,
    event_type character varying(255) NOT NULL,
    file_id uuid NOT NULL,
    message character varying(255) NOT NULL,
    metadata jsonb NOT NULL,
    CONSTRAINT pipeline_events_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['PIPELINE_STARTED'::character varying, 'CHUNKING_STARTED'::character varying, 'CHUNKING_COMPLETE'::character varying, 'CHUNK_UPLOAD_STARTED'::character varying, 'CHUNK_UPLOAD_COMPLETE'::character varying, 'MEDIA_CHUNK_READY'::character varying, 'VISION_ANALYSIS_STARTED'::character varying, 'VISION_ANALYSIS_COMPLETE'::character varying, 'TRANSCRIPTION_STARTED'::character varying, 'TRANSCRIPTION_COMPLETE'::character varying, 'EMBEDDING_COMPLETE'::character varying, 'PIPELINE_COMPLETE'::character varying])::text[])))
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id character varying(255) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    email character varying(255) NOT NULL,
    last_login_at timestamp(6) without time zone NOT NULL,
    name character varying(255) NOT NULL,
    picture_url character varying(255)
);


--
-- Name: file_metadata file_metadata_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.file_metadata
    ADD CONSTRAINT file_metadata_pkey PRIMARY KEY (id);


--
-- Name: media_chunk media_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_chunk
    ADD CONSTRAINT media_chunk_pkey PRIMARY KEY (id);


--
-- Name: pipeline_events pipeline_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_events
    ADD CONSTRAINT pipeline_events_pkey PRIMARY KEY (id);


--
-- Name: users uk6dotkott2kjsp8vw4d0m25fb7; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- PostgreSQL database dump complete
--


