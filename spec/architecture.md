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
  tenant/                       Tenant, TenantService (create/find/suspend), AdminEmails
  tenant/local/                 single-tenant compatibility layer: the configured tenant, the password/loopback
                                admin sign-in and the Socket Mode pilot registration (nothing else may use it)
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
  slack/                        SlackGateway (Bolt app), event handlers, message rendering, SlackWorkspaces
                                (workspace registry) and SlackMessengers (a Slack client per tenant)
  web/                          /health, status page (landing page in multi-tenant mode)
  web/slack/                    /slack/events and /slack/interactions (signed), Add to Slack install flow
  web/admin/                    admin guard, Sources page (connect Drive, pick folders, sync now), Start page,
                                Sign in with Slack (AdminLoginController, SlackAdminAuthenticator)
  crypto/                       TokenCipher (AES-GCM for stored grants)
src/main/resources/
  application.yml
  db/migration/V*.sql
  templates/                    Thymeleaf pages
src/test/java/com/docs0/        mirrors main
```

## Conventions

- **Tenant scoping.** Every table except `tenant` has `tenant_id NOT NULL`. Every
  repository and service method takes a tenant id explicitly; no query runs without it, and there is no
  ambient "current tenant" (see [Tenant resolution](#tenant-resolution-phase-7a)). Every statement carries
  `tenant_id` in its `WHERE`/`JOIN`, and ids that arrive from a user (document, correction, draft, source,
  channel ids) are only ever looked up together with the caller's tenant id, so another tenant's id behaves
  like an id that does not exist. As a backstop, V8 adds composite foreign keys `(child_id, tenant_id) ->
  (parent id, tenant_id)` so the database itself refuses a row that points at another tenant's row.
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

- `tenant(id, slug, name, slack_team_id, created_at, monthly_budget_usd, status, admin_emails)` — the last
  three are Phase 7a: the tenant's own monthly cap (null: the global default), `ACTIVE|SUSPENDED`, and a
  comma-separated list of admins (used by Phase 7b).
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

## Tenant resolution (Phase 7a)

One deployment serves many companies. Nothing looks a tenant up globally: the tenant is established once, at
the edge of each kind of work, and passed down as an explicit `tenantId` argument (a plain parameter rather
than a thread-local or request-scoped holder, so code cannot run for a tenant nobody named).

| Work | Where the tenant comes from |
|---|---|
| An admin request (`/admin/**`) | `AdminGuard` asks the one `AdminAuthenticator` who is signed in. It returns an `AdminPrincipal(tenantId, displayName)`, stored on the request; controllers receive it as a method parameter. Pilot: `LocalAdminAuthenticator` (DOCS0_ADMIN_PASSWORD over HTTP Basic, or loopback when no password is set), which maps to the configured tenant. Multi-tenant mode (Phase 7b): `SlackAdminAuthenticator` is primary and reads the `AdminPrincipal` that Sign in with Slack stored in the server-side session; only a request carrying the operator password falls through to the pilot sign-in, and the loopback shortcut is off. |
| A Slack event, reaction or button click | The payload's workspace (`team_id`), over Socket Mode or HTTP. `SlackGateway` looks it up in `SlackWorkspaces.byTeamId` and calls the handler with that tenant id; an event from a workspace nobody registered, or that removed docs0, is ignored (only the team id is logged). Handlers never read a tenant from anywhere else. |
| A scheduled job (`SyncScheduler`, startup sync, weekly digest) | Loops over `TenantService.active()`. Each tenant, and within it each configured connector, is tried on its own, so one failure never stops the rest. A suspended tenant is skipped. |
| `/` and `/health` | Pilot: the configured local tenant only (`LocalTenantResolver`), as before. Multi-tenant mode: a public landing page and `{"status":"UP"}`. They never show any tenant's name or counts beyond the pilot's own. |

`StatusContributor.status(tenantId)` and `SourceConnector.configured(tenantId)` take the tenant they report on;
global components (the database, the model) ignore it.

`tenant/local` is the single-tenant compatibility layer: `LocalTenantResolver` (configured `docs0.tenant.*` to a
tenant), `LocalAdminAuthenticator` and `LocalSlackPilot` (the Socket Mode registration below). Only
`SlackGateway` and `StatusController` use it, by design; a test fails if anything else does, or if any code calls
a "current tenant" lookup.

### Slack workspace registry

`slack_installation(id, tenant_id UNIQUE, slack_team_id UNIQUE, team_name, encrypted_bot_token, bot_user_id,
installed_by, scopes, created_at, updated_at)`: one workspace per tenant and one tenant per workspace. The bot
token is AES-256-GCM encrypted with `DOCS0_ENCRYPTION_KEY`, decrypted only to build a client, and never logged.

- `SlackWorkspaces`: `byTeamId`, `forTenant`, `register`, `botToken`, `markRevoked`. A revoked installation
  (`revoked_at`, V9) is invisible to all of them until the workspace registers again.
- `SlackMessengers.forTenant(tenantId)`: the `SlackMessenger` for that tenant's workspace (a `BoltSlackMessenger`
  on the tenant's own token), cached per tenant and rebuilt when the tenant is registered again. Every class that
  talks to Slack (question, reaction and draft handlers, corrections, identities, proactive answers, stale-document
  pings, the digest, Slack history, the admin pages) gets its messenger through it. There is no client with a
  global token.
- Pilot: at startup `SlackGateway` calls `auth.test` with `DOCS0_SLACK_BOT_TOKEN` (retrying as before, never
  crashing startup) and registers the result for the configured tenant. From then on the pilot is routed through
  the same registry as any other workspace. This needs `DOCS0_ENCRYPTION_KEY`.
- In-memory state is keyed by tenant: event de-duplication, per-conversation queues, identity-scope warnings,
  Slack-history name caches and skip notes. Slack ids (channels, threads, users) are only unique within a
  workspace.

### Per-tenant limits and settings

- `BudgetService.monthlyCap(tenantId)`: the tenant's own `monthly_budget_usd` when it is positive, else the
  global `DOCS0_MONTHLY_BUDGET_USD`. The cap is checked where it always was (questions, repeat-question answers,
  themes, the digest).
- `status = SUSPENDED`: questions are refused politely, draft buttons do nothing, and syncs and scheduled jobs
  skip the tenant.
- `TenantService` creates and finds tenants by id, slug and Slack team id. The first tenant stays `smalltech`,
  created from configuration.



## Self-serve onboarding (Phase 7b)

Multi-tenant mode is on when `docs0.slack.client-id`, `client-secret` and `signing-secret` are all set
(`Docs0Properties.multiTenant()`); everything below is inert otherwise.

**Slack transports.** Socket Mode (pilot) and HTTP deliver the same payloads to the same entry points,
`SlackGateway.appMention`, `message`, `reactionAdded`, `reactionRemoved` and `blockAction`; the mapping to
`IncomingEvent`/`ReactionEvent`/`DraftClick` and the tenant lookup are shared, so the transport is the only
difference. For HTTP, `SlackEventsController` reads the raw body once, verifies `SlackSignature` on the bytes
(constant-time, 5-minute window; bare 401 otherwise), answers `url_verification`, replies 200 immediately and runs
`SlackHttpEvents` on a virtual thread. `SlackHttpEvents` parses the verified JSON with the Slack SDK model classes
(a subtyped `message` is not a question, as in Bolt) and handles `app_uninstalled`/`tokens_revoked`. Retries
(`X-Slack-Retry-Num`) of an event id already seen are dropped; the handlers de-duplicate by event id and by
channel and ts as well. No servlet adapter for Bolt is on the classpath, and none was added.

**OAuth.** `SlackOAuth` (oauth.v2.access) and `SlackSignIn` (openid.connect.token and userInfo) are the network
edges, faked in tests like `GoogleOAuth`. State (and nonce) are random, single-use, bound to the browser session
and expire after 10 minutes (`Clock` bean). `SlackInstaller` turns an install into a tenant plus an encrypted
installation, then greets the installer.

**Sessions.** The admin session holds an `AdminPrincipal`; `AdminGuard` is unchanged (CSRF token per session on
every non-read request). `/admin/login` and `/admin/login/callback` are excluded from the guard only in this mode.
`MultiTenantConfig` sets the session cookie flags and warns at startup when the public URL is not https.
