# Architecture

## Stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | Java 25 | Binding user requirement (Java). 25 is the JDK installed on the build machine. |
| Framework | Spring Boot 4.1 (Web MVC, JDBC, Scheduling, Actuator) | Assumed: the mainstream Java service framework; gives config, scheduling, health and a web layer for the dashboard in one process. |
| Build | Maven via the checked-in wrapper `./mvnw` | No global build tool needed; the wrapper pins the version. |
| Database | PostgreSQL 14+ | One store for content, the keyword index, conversations and corrections. Full-text search (`tsvector` + GIN) is the retrieval index. No vector extension. |
| Migrations | Flyway (`src/main/resources/db/migration`) | Schema changes are versioned and verifiable. |
| Data access | Spring `JdbcClient` with hand-written SQL | Full-text queries and tenant scoping are explicit; no ORM. |
| LLM | Anthropic via `com.anthropic:anthropic-java`, model `claude-sonnet-5-5` (env-overridable) | User chose Anthropic and Sonnet 5.5 for cost. |
| Slack | Bolt for Java, Socket Mode | User chose Socket Mode: no public URL while piloting. |
| Google Drive | `google-api-services-drive` v3 with the admin's own Google sign-in (OAuth 2.0 authorization-code flow, offline access, `drive.readonly`) | User decision: no service account; Drive is connected from the dashboard. |
| Text extraction | Drive export for Google Docs/Sheets/Slides; Apache PDFBox for PDFs | Whole-document plain text is what the agent reads. |
| Web UI | Server-rendered HTML (Thymeleaf) served by the same app | The dashboard is small; no separate front-end build. |
| Tests | JUnit 5, Spring Boot Test, real local Postgres (`docs0_test`) | Production DB engine in tests; fakes only for Slack, Drive and Anthropic network edges. |

There is deliberately no embedding model and no vector store (user decision).

## Layout

```
pom.xml, mvnw, .mvn/            build
.env.example                    every env var, documented
slack/manifest.yml              Slack app manifest to paste at api.slack.com
spec/                           this spec
NOTES.md                        build journal (harness friction only)
src/main/java/com/docs0/
  Docs0Application.java
  config/                       typed settings (Docs0Properties), .env loading, beans
  tenant/                       Tenant, TenantRepository, bootstrap of the first tenant
  knowledge/                    Document model, DocumentRepository (upsert, search, read)
  source/                       SourceConnector SPI, SyncService, SyncRun log
  source/drive/                 DriveClient, DriveConnector, text extraction
  source/linear/, source/trello/  LinearClient/TrelloClient (network edge), connection, connector, rendering
  source/slack/                 Slack history connector (channel selection, rendering, sync)
  source/gmail/                 GmailClient (network edge), service-account token source, connection, connector
  action/                       PendingAction, drafts repository, ActionService (the only writes)
  budget/                       BudgetService: hard monthly spend cap
  agent/                        AgentService (tool loop), tools, AnswerVerifier, LlmClient
  conversation/                 Conversation + Interaction log
  slack/                        SlackGateway (Bolt app), event handlers, message rendering
  web/                          /health, status page
  web/admin/                    admin guard, Sources page (connect Drive, pick folders, sync now)
  crypto/                       TokenCipher (AES-GCM for stored grants)
src/main/resources/
  application.yml
  db/migration/V*.sql
  templates/                    Thymeleaf pages
src/test/java/com/docs0/        mirrors main
```

## Conventions

- **Tenant scoping.** Every table except `tenant` has `tenant_id NOT NULL`. Every
  repository method takes a tenant id; no query runs without it.
- **Config.** All settings come from env vars prefixed `DOCS0_`, bound to
  `Docs0Properties`. A `.env` file at the repo root is loaded at startup if present. Never
  read `.env` in tests or print a secret.
- **Degraded boot.** The app must start with any external credential missing. A missing
  Slack, Drive or Anthropic credential disables that integration and shows on the status
  page; it never crashes startup.
- **Network edges behind interfaces.** `LlmClient`, `DriveClient`, `LinearClient`, `TrelloClient`
  and `SlackMessenger` are interfaces so tests substitute fakes. Everything else is tested for real.
- **One LLM loop per question.** Never call the model per document, per line or per
  token of output.
- **Run command.** `./mvnw spring-boot:run` from the repo root. Port from
  `DOCS0_PORT` (default 8080).
- **Gate.** `./mvnw -q verify` runs all tests against `docs0_test`.
- **Logging.** SLF4J. Never log document bodies, tokens or keys; log ids and counts.
- **Time.** `Instant`/`timestamptz` everywhere, UTC.

## Data model (Phase 1)

- `tenant(id, slug, name, slack_team_id, created_at)`
- `source(id, tenant_id, kind, name, config jsonb, visibility, created_at)` — `kind` is
  `GOOGLE_DRIVE` in Phase 1; its `config` holds the chosen folders as
  `{"folders":[{"id":"…","name":"…"}]}`. `visibility` is `SHARED` or `RESTRICTED`.
- `document(id, tenant_id, source_id, external_id, title, mime_type, url, owner_name,
  owner_email, modified_at, body, body_chars, content_hash, search tsvector, deleted,
  synced_at)` — unique on `(tenant_id, source_id, external_id)`. `search` is a generated
  column: title weighted A, body weighted B. GIN index on `search`.
- `source_credential(id, tenant_id, source_id UNIQUE, account_email, encrypted_refresh_token,
  encrypted_secret, created_at)` — the stored secret of a source. Google keeps its refresh token
  in `encrypted_refresh_token`; Linear and Trello keep encrypted JSON in `encrypted_secret`
  (Phase 3); at least one is set. `account_email` is the display name of the account. Everything
  is AES-256-GCM encrypted with `DOCS0_ENCRYPTION_KEY`; it is never logged or shown.
- `sync_run(id, tenant_id, source_id, started_at, finished_at, status, added, updated,
  removed, skipped, failed, error)`
- `conversation(id, tenant_id, channel_id, thread_ts, is_dm, created_at)` — unique on
  `(tenant_id, channel_id, thread_ts)`. For a DM, `thread_ts` is the empty string: the
  whole DM is one conversation.
- `interaction(id, tenant_id, conversation_id, slack_user_id, question, answer, kind,
  confidence, citations jsonb, steps, input_tokens, output_tokens, cost_usd, duration_ms,
  error, created_at)`

Phase 2 adds:

- `feedback(id, tenant_id, interaction_id, slack_user_id, rating UP|DOWN, created_at)` —
  unique on `(interaction_id, slack_user_id)`.
- `correction(id, tenant_id, interaction_id, author_slack_id, author_name, question,
  original_answer, correction_text, edited_text, status PENDING|APPROVED|REJECTED, from_dm,
  created_at, reviewed_at, search tsvector)` — `search` covers the question and the edited
  (else extracted) text, GIN indexed.
- `feedback_prompt(id, tenant_id, conversation_id, interaction_id UNIQUE, created_at,
  cleared_at)` — a thread waiting for a correction.
- `interaction.correction_ids jsonb` — approved corrections shown to the model for that answer.

Phase 3 adds:

- `source.kind` values `LINEAR` (config `{"teams":[{id,key,name}]}`) and `TRELLO` (config
  `{"boards":[{id,name}]}`), both `SHARED`.
- `pending_action(id, tenant_id, conversation_id, interaction_id nullable, requested_by_slack_id,
  kind LINEAR_ISSUE|TRELLO_CARD, params jsonb, status PENDING|DONE|CANCELLED|EXPIRED|FAILED,
  result_url, error, created_at, resolved_at)` — an issue or card drafted for the asker to
  confirm. `DONE` is set when the confirm click claims the draft, just before the item is
  created; `result_url` is filled when it exists, and a failed create becomes `FAILED`.

Phase 4a adds (migration `V5__access_control.sql`):

- `document.restricted boolean not null default false` — existing documents stay unrestricted.
- `document_principal(tenant_id, document_id, principal text, PRIMARY KEY (document_id, principal))`,
  deleted with its document, index on `(tenant_id, principal)`. A principal is a lowercase string:
  a Slack user id (`u…`) or an email.
- `identity(tenant_id, slack_user_id, email, display_name, is_bot, deleted, updated_at)`, unique on
  `(tenant_id, slack_user_id)` — a cache of Slack users, refreshed when older than 24 hours. `email`
  is null when the app lacks the `users:read.email` scope. (`deleted` marks deactivated users.)
- `source.kind` value `SLACK` (name `Slack history`, config `{"channels":[{"id","name","private"}]}`).

Phase 5 adds (migration `V6__gmail_and_private_answers.sql`):

- `document.version text` (nullable): the source's version of an item, used to skip unchanged items
  (Gmail's thread `historyId`).
- `interaction.used_restricted boolean not null default false`: set when the answer cited a restricted
  document; admin pages also join the cited ids to `document.restricted`.
- `source.kind` value `GMAIL` (name `Gmail`, visibility `SHARED`, every document restricted). Its secret is
  the service account key JSON in `source_credential.encrypted_secret`; `config` is
  `{"mailboxes":[{"email","viewers":[...]}], "adminEmail": "..."}`. Each thread's principals are the mailbox
  email plus its viewers.

Later phases add channel settings.

## Access model (implemented in Phase 4a)

A document is either **shared** (`restricted = false`, the default: Drive, Linear, Trello, public
Slack channels) or **restricted** (`restricted = true`), in which case `document_principal` holds who
may see it: for a private Slack channel, the channel's current members (Slack ids, plus their emails
when known); for Gmail, the mailbox owner's email plus any extra viewers of that mailbox. Writes replace the principal set (`DocumentStore.
upsertRestricted`) and `refreshAccess` brings unchanged documents up to date, so a membership change
lands at the next sync.

When answering, the conversation **audience** is every member of the channel docs0 answers in, or the
one person in a DM. It is built by the Slack layer from `conversations.members`, resolved through the
`identity` cache to `(slackUserId, email?)` pairs; bots, deactivated users and docs0 itself are dropped;
a person Slack cannot describe stays in by id only; if the member list of a channel cannot be read the audience is
empty, so only shared knowledge is used (everyone in the channel sees the answer, so the asker's own
access is not enough). A channel with fewer than two people (not counting bots) is treated as an unknown audience and sees
shared knowledge only, because more people can join it later. DMs and group DMs are never indexed.

**Eligibility rule.** A restricted document is eligible only if the audience has at least one member
and every member is covered, where a member is covered when their lowercase Slack id or lowercase email
is one of the document's principals. An empty audience sees no restricted documents. The rule is one
SQL predicate (`JdbcKnowledgeSearch.ELIGIBLE`) used by `search` (including the any-term retry and the
source-kind filter), `findById` and `recent`; a hidden document leaves no hit, snippet, title or
existence signal, and `findById` of a hidden id is empty exactly like a missing id. `count` stays
tenant-wide. `AgentService`, `KnowledgeTools` and `AnswerVerifier` pass the request's audience to every
call, so the verifier cannot confirm a quote from a document the audience cannot see.
