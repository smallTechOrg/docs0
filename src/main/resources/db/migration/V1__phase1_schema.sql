-- Phase 1 schema. Every table except tenant is tenant-scoped.

CREATE TABLE tenant (
    id            BIGSERIAL PRIMARY KEY,
    slug          TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    slack_team_id TEXT UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE source (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    kind       TEXT        NOT NULL,                  -- GOOGLE_DRIVE, ...
    name       TEXT        NOT NULL,
    config     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    visibility TEXT        NOT NULL DEFAULT 'SHARED', -- SHARED | RESTRICTED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, kind, name)
);

CREATE TABLE document (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL REFERENCES tenant (id),
    source_id    BIGINT      NOT NULL REFERENCES source (id),
    external_id  TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    mime_type    TEXT,
    url          TEXT,
    owner_name   TEXT,
    owner_email  TEXT,
    modified_at  TIMESTAMPTZ,
    body         TEXT        NOT NULL DEFAULT '',
    body_chars   INTEGER     NOT NULL DEFAULT 0,
    content_hash TEXT,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    synced_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Keyword index: title outranks body. Body is capped to stay under the tsvector size limit.
    search       TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', left(coalesce(body, ''), 400000)), 'B')
    ) STORED,
    UNIQUE (tenant_id, source_id, external_id)
);
CREATE INDEX document_search_idx ON document USING GIN (search);
CREATE INDEX document_tenant_modified_idx ON document (tenant_id, modified_at DESC) WHERE NOT deleted;

CREATE TABLE sync_run (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    source_id   BIGINT      NOT NULL REFERENCES source (id),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status      TEXT        NOT NULL DEFAULT 'RUNNING', -- RUNNING | OK | FAILED
    added       INTEGER     NOT NULL DEFAULT 0,
    updated     INTEGER     NOT NULL DEFAULT 0,
    removed     INTEGER     NOT NULL DEFAULT 0,
    skipped     INTEGER     NOT NULL DEFAULT 0,
    failed      INTEGER     NOT NULL DEFAULT 0,
    error       TEXT
);
CREATE INDEX sync_run_tenant_started_idx ON sync_run (tenant_id, started_at DESC);

CREATE TABLE conversation (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    channel_id TEXT        NOT NULL,
    thread_ts  TEXT        NOT NULL DEFAULT '',  -- '' for a DM: the whole DM is one conversation
    is_dm      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel_id, thread_ts)
);

CREATE TABLE interaction (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT         NOT NULL REFERENCES tenant (id),
    conversation_id BIGINT         NOT NULL REFERENCES conversation (id),
    slack_user_id   TEXT           NOT NULL,
    question        TEXT           NOT NULL,
    answer          TEXT,
    kind            TEXT,                       -- ANSWER | CLARIFY | UNKNOWN | ERROR | OVER_BUDGET
    confidence      TEXT,                       -- HIGH | MEDIUM | LOW
    citations       JSONB          NOT NULL DEFAULT '[]'::jsonb,
    steps           INTEGER        NOT NULL DEFAULT 0,
    input_tokens    BIGINT         NOT NULL DEFAULT 0,
    output_tokens   BIGINT         NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12, 6) NOT NULL DEFAULT 0,
    duration_ms     BIGINT         NOT NULL DEFAULT 0,
    error           TEXT,
    answer_ts       TEXT,                       -- Slack ts of docs0's reply, for later reactions
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX interaction_conversation_idx ON interaction (conversation_id, created_at);
CREATE INDEX interaction_tenant_created_idx ON interaction (tenant_id, created_at DESC);
