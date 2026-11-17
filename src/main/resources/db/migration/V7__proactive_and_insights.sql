-- Phase 6: proactive answers, stale-document flags and insights. Every table is tenant-scoped.

-- Per-channel settings. Nothing is opted in until an admin ticks the channel.
CREATE TABLE channel_setting (
    tenant_id    BIGINT      NOT NULL REFERENCES tenant (id),
    channel_id   TEXT        NOT NULL,
    channel_name TEXT        NOT NULL DEFAULT '',
    proactive    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, channel_id)
);

-- Small key/value settings: the digest channel, when the last digest went out, cached themes.
CREATE TABLE tenant_setting (
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    key        TEXT        NOT NULL,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key)
);

-- Model calls that are not a user's question (judging a repeat question, clustering themes).
-- They count toward the monthly cap together with the interaction log.
CREATE TABLE usage_log (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT         NOT NULL REFERENCES tenant (id),
    purpose       TEXT           NOT NULL,
    input_tokens  BIGINT         NOT NULL DEFAULT 0,
    output_tokens BIGINT         NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12, 6) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX usage_log_tenant_created_idx ON usage_log (tenant_id, created_at DESC);

-- Past questions, searchable by keyword, for repeat-question detection.
ALTER TABLE interaction ADD COLUMN question_search TSVECTOR GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(question, ''))
) STORED;
CREATE INDEX interaction_question_search_idx ON interaction USING GIN (question_search);

-- One row per channel message docs0 looked at closely enough to judge it. At most one per message,
-- which is also what keeps docs0 from answering the same message twice. judged_same is true when
-- docs0 offered an answer; interaction_id is that answer once it is logged.
CREATE TABLE proactive_offer (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT      NOT NULL REFERENCES tenant (id),
    channel_id             TEXT        NOT NULL,
    message_ts             TEXT        NOT NULL,
    interaction_id         BIGINT      REFERENCES interaction (id),
    matched_interaction_id BIGINT      NOT NULL REFERENCES interaction (id),
    judged_same            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel_id, message_ts)
);
CREATE INDEX proactive_offer_channel_created_idx ON proactive_offer (tenant_id, channel_id, created_at DESC);

-- An answer that relied on a document last updated long ago.
CREATE TABLE stale_flag (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL REFERENCES tenant (id),
    document_id          BIGINT      NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    interaction_id       BIGINT      NOT NULL REFERENCES interaction (id),
    document_modified_at TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_pinged_at      TIMESTAMPTZ,
    UNIQUE (interaction_id, document_id)
);
CREATE INDEX stale_flag_tenant_document_idx ON stale_flag (tenant_id, document_id, created_at DESC);
