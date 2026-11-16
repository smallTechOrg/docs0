# docs0

A knowledge agent for your company's docs and tools. The team asks it questions in Slack;
it answers from the company's own content with source links and quoted snippets.

docs0 does not use vector search or embeddings. Claude searches a keyword index, opens
whole documents, and answers only from what it read. Every quote in an answer is checked
against the source document before it is shown.

**Status:** Phase 1 — answers from Google Drive. Linear, Trello, Grain, Slack history and
Gmail follow, one phase at a time. See [spec/roadmap.md](spec/roadmap.md).

## What you need

- Java 25
- PostgreSQL 14 or newer, running locally, with a database named `docs0`
  (`createdb docs0`; tests use `docs0_test`)
- An Anthropic API key
- A Slack workspace where you can create an app
- A Google account that can open the Drive folders you want indexed, and a Google Cloud
  project where you can create an OAuth client

## Setup

Copy `.env.example` to `.env` and fill it in as you go. `.env` is git-ignored.

### 1. Anthropic

Set `DOCS0_ANTHROPIC_API_KEY`. The default model is `claude-sonnet-5-5`.
`DOCS0_MONTHLY_BUDGET_USD` is a hard monthly cap: docs0 stops answering once the logged
cost of the month's answers reaches it.

### 2. Slack app

1. Go to <https://api.slack.com/apps> → **Create New App** → **From a manifest**, pick
   your workspace, and paste [slack/manifest.yml](slack/manifest.yml).
2. **Install to Workspace**. Copy the **Bot User OAuth Token** (`xoxb-…`) from
   *OAuth & Permissions* into `DOCS0_SLACK_BOT_TOKEN`.
3. Under *Basic Information → App-Level Tokens*, generate a token with the
   `connections:write` scope. Copy it (`xapp-…`) into `DOCS0_SLACK_APP_TOKEN`.
4. Invite the bot to the channels where the team will use it: `/invite @docs0`.

docs0 connects out to Slack over Socket Mode, so it needs no public URL.

### 3. Google Drive

You connect Drive by signing in with Google on docs0's Sources page. That needs a Google
OAuth client, created once:

1. In [Google Cloud Console](https://console.cloud.google.com), create or pick a project.
   Under **APIs & Services → Library**, enable the **Google Drive API**.
2. Under **APIs & Services → OAuth consent screen**, set the app up with user type
   **Internal** (for a Google Workspace organisation, this limits sign-in to your own
   people and needs no review by Google).
3. Under **APIs & Services → Credentials → Create credentials → OAuth client ID**, choose
   **Web application** and add this **Authorized redirect URI**:
   `http://localhost:8080/admin/sources/google/callback`
4. Put three values in `.env`:
   - `DOCS0_GOOGLE_OAUTH_CLIENT_ID` — the client ID
   - `DOCS0_GOOGLE_OAUTH_CLIENT_SECRET` — the client secret
   - `DOCS0_ENCRYPTION_KEY` — a key of your own, made with `openssl rand -base64 32`.
     docs0 encrypts the stored Google sign-in with it.
5. Start docs0 (see [Run](#run)) and open <http://localhost:8080/admin/sources>. Click
   **Connect Google Drive** and sign in. Pick the folders to index from My Drive, Shared
   with me or your shared drives, then click **Sync now**.

docs0 gets read-only access and indexes only the folders you pick, including everything
inside them. Nothing is indexed until you pick a folder. You can change the folders,
reconnect or disconnect on the same page at any time.

Indexed types: Google Docs, Sheets and Slides, PDFs, and plain text, Markdown and CSV
files. docs0 syncs at startup and every 15 minutes.

The Sources page is an admin page. Out of the box it opens only in a browser on the
machine docs0 runs on. To reach it from elsewhere, set `DOCS0_ADMIN_PASSWORD` and sign in
as user `admin`; set it too whenever docs0 sits behind a proxy. If docs0 is not at
`http://localhost:8080`, set `DOCS0_PUBLIC_URL` to its address, register
`<DOCS0_PUBLIC_URL>/admin/sources/google/callback` as the redirect URI, and open the
Sources page at that same address.

## Run

```
./mvnw spring-boot:run
```

Open <http://localhost:8080> for the status page: connection state for Slack, Anthropic
and Drive, the document count, the last sync and this month's spend, with a link to the
Sources page. `/health` returns the same as JSON. The app starts even when a credential is missing; the status page says
what is not set up.

## Use

- Mention it in a channel: `@docs0 what is our refund policy?` It answers in a thread.
- Reply in that thread to ask a follow-up. No mention needed.
- Send it a direct message for a private conversation.

Each answer lists its sources with the exact passage it relied on. When docs0 is unsure
it says so, asks a clarifying question, or names who might know.

## Test

```
./mvnw verify
```

Tests run against the real `docs0_test` Postgres database. They never use real Slack,
Drive or Anthropic credentials.

## Design

- [spec/brief.md](spec/brief.md) — what docs0 is and the decisions behind it
- [spec/architecture.md](spec/architecture.md) — stack, layout, data model, access model
- [spec/agent.md](spec/agent.md) — how the agent searches, answers and verifies quotes
- [spec/capabilities.md](spec/capabilities.md) — behaviour, capability by capability
- [spec/roadmap.md](spec/roadmap.md) — phases
