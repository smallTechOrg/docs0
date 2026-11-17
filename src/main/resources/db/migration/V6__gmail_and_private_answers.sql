-- Phase 5: Gmail.
-- A per-document version lets a connector skip items whose source version did not change
-- (Gmail's thread historyId). Null for sources that do not use it.
ALTER TABLE document ADD COLUMN version TEXT;

-- True when the answer cited a restricted document (a private Slack channel, a mailbox), decided when the
-- answer was logged. Admin pages also join the cited ids to document.restricted, so answers logged
-- before this column existed are covered too.
ALTER TABLE interaction ADD COLUMN used_restricted BOOLEAN NOT NULL DEFAULT FALSE;
