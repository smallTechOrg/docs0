-- Phase 2: reactions, corrections and the questions that wait for one. Tenant-scoped.

CREATE TABLE feedback (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant (id),
    interaction_id BIGINT      NOT NULL REFERENCES interaction (id),
    slack_user_id  TEXT        NOT NULL,
    rating         TEXT        NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (interaction_id, slack_user_id)
);
CREATE INDEX feedback_tenant_rating_idx ON feedback (tenant_id, rating);

CREATE TABLE correction (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant (id),
    interaction_id  BIGINT      REFERENCES interaction (id),
    author_slack_id TEXT        NOT NULL,
    author_name     TEXT        NOT NULL,
    question        TEXT        NOT NULL,   -- the question the corrected answer was given to
    original_answer TEXT        NOT NULL DEFAULT '',
    correction_text TEXT        NOT NULL,   -- the corrected fact, as extracted
    edited_text     TEXT,                   -- the admin's reworded version, when they changed it
    status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    from_dm         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at     TIMESTAMPTZ,
    search          TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(question, '') || ' ' || coalesce(edited_text, correction_text, ''))
    ) STORED
);
CREATE INDEX correction_search_idx ON correction USING GIN (search);
CREATE INDEX correction_tenant_status_idx ON correction (tenant_id, status, created_at DESC);

-- Marks a thread where docs0 asked "what was wrong?". One per answer, ever: cleared_at is set
-- when a correction arrives; a prompt older than 24 hours no longer waits.
CREATE TABLE feedback_prompt (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant (id),
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id),
    interaction_id  BIGINT      NOT NULL REFERENCES interaction (id) UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    cleared_at      TIMESTAMPTZ
);
CREATE INDEX feedback_prompt_conversation_idx ON feedback_prompt (conversation_id);

-- Which approved corrections were shown to the model for this answer.
ALTER TABLE interaction ADD COLUMN correction_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
