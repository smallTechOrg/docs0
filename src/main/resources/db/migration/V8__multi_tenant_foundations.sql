-- Phase 7a: multi-tenant foundations.
-- Per-tenant limits, the Slack workspace registry, and database-level guards so a row can never
-- point at another tenant's row.

-- Per-tenant limits. monthly_budget_usd NULL means "use DOCS0_MONTHLY_BUDGET_USD". A SUSPENDED tenant's
-- questions are refused politely and its syncs and scheduled jobs are skipped. admin_emails is a
-- comma-separated list of people who may sign in to this tenant's admin pages (used by Phase 7b).
ALTER TABLE tenant ADD COLUMN monthly_budget_usd NUMERIC(12, 2);
ALTER TABLE tenant ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED'));
ALTER TABLE tenant ADD COLUMN admin_emails TEXT;

-- One Slack workspace per tenant. The bot token is AES-256-GCM encrypted with DOCS0_ENCRYPTION_KEY.
CREATE TABLE slack_installation (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL UNIQUE REFERENCES tenant (id),
    slack_team_id       TEXT        NOT NULL UNIQUE,
    team_name           TEXT,
    encrypted_bot_token TEXT        NOT NULL,
    bot_user_id         TEXT,
    installed_by        TEXT,
    scopes              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Composite keys: a child row can only reference a parent row of the same tenant.
ALTER TABLE source       ADD CONSTRAINT source_id_tenant_uq       UNIQUE (id, tenant_id);
ALTER TABLE document     ADD CONSTRAINT document_id_tenant_uq     UNIQUE (id, tenant_id);
ALTER TABLE conversation ADD CONSTRAINT conversation_id_tenant_uq UNIQUE (id, tenant_id);
ALTER TABLE interaction  ADD CONSTRAINT interaction_id_tenant_uq  UNIQUE (id, tenant_id);

ALTER TABLE document ADD CONSTRAINT document_source_tenant_fk
    FOREIGN KEY (source_id, tenant_id) REFERENCES source (id, tenant_id);
ALTER TABLE sync_run ADD CONSTRAINT sync_run_source_tenant_fk
    FOREIGN KEY (source_id, tenant_id) REFERENCES source (id, tenant_id);
ALTER TABLE source_credential ADD CONSTRAINT source_credential_source_tenant_fk
    FOREIGN KEY (source_id, tenant_id) REFERENCES source (id, tenant_id);
ALTER TABLE document_principal ADD CONSTRAINT document_principal_document_tenant_fk
    FOREIGN KEY (document_id, tenant_id) REFERENCES document (id, tenant_id) ON DELETE CASCADE;
ALTER TABLE interaction ADD CONSTRAINT interaction_conversation_tenant_fk
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversation (id, tenant_id);
ALTER TABLE feedback ADD CONSTRAINT feedback_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE correction ADD CONSTRAINT correction_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE feedback_prompt ADD CONSTRAINT feedback_prompt_conversation_tenant_fk
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversation (id, tenant_id);
ALTER TABLE feedback_prompt ADD CONSTRAINT feedback_prompt_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE pending_action ADD CONSTRAINT pending_action_conversation_tenant_fk
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversation (id, tenant_id);
ALTER TABLE pending_action ADD CONSTRAINT pending_action_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE proactive_offer ADD CONSTRAINT proactive_offer_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE proactive_offer ADD CONSTRAINT proactive_offer_matched_tenant_fk
    FOREIGN KEY (matched_interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
ALTER TABLE stale_flag ADD CONSTRAINT stale_flag_document_tenant_fk
    FOREIGN KEY (document_id, tenant_id) REFERENCES document (id, tenant_id) ON DELETE CASCADE;
ALTER TABLE stale_flag ADD CONSTRAINT stale_flag_interaction_tenant_fk
    FOREIGN KEY (interaction_id, tenant_id) REFERENCES interaction (id, tenant_id);
