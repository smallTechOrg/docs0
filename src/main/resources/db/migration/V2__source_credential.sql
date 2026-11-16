-- The Google grant an admin gave when connecting a source. One per source.
-- The refresh token is AES-256-GCM encrypted with DOCS0_ENCRYPTION_KEY.

CREATE TABLE source_credential (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT      NOT NULL REFERENCES tenant (id),
    source_id               BIGINT      NOT NULL UNIQUE REFERENCES source (id),
    account_email           TEXT        NOT NULL,
    encrypted_refresh_token TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
