-- Phase 3: Linear and Trello.
--
-- source_credential also holds secrets that are not a Google refresh token: a Linear API key,
-- or a Trello key and token. They live in encrypted_secret as AES-256-GCM encrypted JSON. The
-- Google rows keep their refresh token in encrypted_refresh_token and need no change.
-- account_email is the display name of the connected account: an email for Google, the
-- workspace or account name for Linear and Trello.

ALTER TABLE source_credential ALTER COLUMN encrypted_refresh_token DROP NOT NULL;
ALTER TABLE source_credential ADD COLUMN encrypted_secret TEXT;
ALTER TABLE source_credential ADD CONSTRAINT source_credential_has_secret
    CHECK (encrypted_refresh_token IS NOT NULL OR encrypted_secret IS NOT NULL);

-- A Linear issue or Trello card docs0 has drafted from a Slack thread. Nothing is created in
-- the other tool until the person who asked confirms; see ActionService.
CREATE TABLE pending_action (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL REFERENCES tenant (id),
    conversation_id      BIGINT      NOT NULL REFERENCES conversation (id),
    interaction_id       BIGINT      REFERENCES interaction (id),
    requested_by_slack_id TEXT       NOT NULL,
    kind                 TEXT        NOT NULL CHECK (kind IN ('LINEAR_ISSUE', 'TRELLO_CARD')),
    params               JSONB       NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'DONE', 'CANCELLED', 'EXPIRED', 'FAILED')),
    result_url           TEXT,
    error                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ
);
CREATE INDEX pending_action_tenant_status_idx ON pending_action (tenant_id, status, created_at DESC);
