-- Phase 4a: access-aware knowledge.
-- A restricted document may be used in an answer only if every person who will see the answer
-- is in its principal set. Existing documents stay unrestricted.

ALTER TABLE document ADD COLUMN restricted BOOLEAN NOT NULL DEFAULT FALSE;

-- Who may see a restricted document. A principal is lowercase: a Slack user id or an email.
CREATE TABLE document_principal (
    tenant_id   BIGINT NOT NULL REFERENCES tenant (id),
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    principal   TEXT   NOT NULL,
    PRIMARY KEY (document_id, principal)
);
CREATE INDEX document_principal_tenant_principal_idx ON document_principal (tenant_id, principal);

-- Cache of Slack users: who they are, whether they count as people, and their email when the app may read it.
CREATE TABLE identity (
    tenant_id     BIGINT      NOT NULL REFERENCES tenant (id),
    slack_user_id TEXT        NOT NULL,
    email         TEXT,
    display_name  TEXT,
    is_bot        BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted       BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slack_user_id)
);
