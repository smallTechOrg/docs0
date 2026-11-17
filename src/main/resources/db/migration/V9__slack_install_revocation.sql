-- Phase 7b: a workspace can remove docs0 (app_uninstalled / tokens_revoked). The installation row is kept, so the
-- tenant and its data survive, but nothing is served for it until the workspace installs docs0 again.
ALTER TABLE slack_installation ADD COLUMN revoked_at TIMESTAMPTZ;
