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
- **Network edges behind interfaces.** `LlmClient`, `DriveClient` and `SlackMessenger`
  are interfaces so tests substitute fakes. Everything else is tested for real.
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
  created_at)` — the Google grant for a source. The token is AES-256-GCM encrypted with
  `DOCS0_ENCRYPTION_KEY`; it is never logged or shown.
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

Later phases add `principal`/ACL tables and channel settings.

## Access model (design, enforced from Phase 4)

Each document belongs to a source. A `SHARED` source is visible to everyone in the
tenant. A `RESTRICTED` source carries a set of principals per document (Slack user ids
for private channels, the mailbox owner for Gmail). When answering, the agent's search
and read tools receive the conversation **audience**: all members of the channel, or the
one person in a DM. A restricted document is eligible only if every audience member is in
its principal set. Phase 1 has only `SHARED` sources, so the audience filter is a no-op,
but the tool signatures already carry it.
