-- V10__conversations.sql
-- Chat persistence (CHAT-AGENT-PLAN §5.2). The tables belong to cortex-rag-orchestration;
-- the migration lives here because this service is the schema authority.
--
-- One conversation per chat, one row per message. An assistant row's content is the
-- answer as structured JSON — the segments AND the sources their cites number — never
-- flattened to prose: citations exist only at generation time, and a reload has to
-- hand them back clickable. The model's replay view (plain question + answer text) is
-- derived from these rows at request time, not stored, so the two cannot drift.
--
-- attached_file_ids is on the message, not the conversation: a user attaches files to
-- one question, and the next has none unless they attach again. It is a uuid[] rather
-- than a foreign key (Postgres cannot FK array elements), and that is wanted — the
-- history should still say "you attached X here" after X is deleted. It exists so the
-- reloaded chat can draw the chips; it is never read back into the prompt.
--
-- user_id IS a foreign key. The gateway syncs the users row at login before the login
-- completes, so no authenticated request can precede it; if that sync ever fails, the
-- FK makes it loud ("log in again") rather than silent (a chat owned by nobody).

CREATE TABLE conversation (
    id          uuid                           NOT NULL,
    user_id     varchar(255)                   NOT NULL,
    title       varchar(255)                   NOT NULL,
    created_at  timestamp(6) without time zone NOT NULL,
    updated_at  timestamp(6) without time zone NOT NULL,
    CONSTRAINT conversation_pkey      PRIMARY KEY (id),
    CONSTRAINT fk_conversation_user   FOREIGN KEY (user_id) REFERENCES users (id)
);

-- The sidebar: one user's conversations, most recent activity first, straight from the index.
CREATE INDEX idx_conversation_user_updated ON conversation (user_id, updated_at DESC);

CREATE TABLE conversation_message (
    id                 uuid                           NOT NULL,
    conversation_id    uuid                           NOT NULL,
    role               varchar(255)                   NOT NULL,
    -- USER rows: {"text": "..."}   ASSISTANT rows: {"segments": [...], "sources": [...]}
    content            jsonb                          NOT NULL,
    attached_file_ids  uuid[]                         NOT NULL DEFAULT '{}',
    created_at         timestamp(6) without time zone NOT NULL,
    CONSTRAINT conversation_message_pkey PRIMARY KEY (id),
    CONSTRAINT conversation_message_role_check CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT fk_conversation_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE CASCADE
);

-- A chat is always read whole, in order. Without this, loading one chat scans every message
-- of every user; with it, only that chat's rows are touched, already sorted.
CREATE INDEX idx_conversation_message_conversation_created
    ON conversation_message (conversation_id, created_at);
