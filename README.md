# docs0

A knowledge agent for your company's docs and tools. The team asks it questions in Slack;
it answers from the company's own content with source links and quoted snippets.

docs0 does not use vector search or embeddings. Claude searches a keyword index, opens
whole documents, and answers only from what it read. Every quote in an answer is checked
against the source document before it is shown.

**Status:** all phases are built (0 scaffold, 1 Drive answers, 2 feedback and corrections, 3 Linear and Trello,
4 Grain, Slack history and audience-aware access, 5 Gmail, 6 repeat-question answers, stale flags and Insights,
7 multi-company onboarding). It answers from Google Drive, Linear, Trello, Grain, Slack history and Gmail
(each mailbox private to its owner), can file a Linear issue or Trello card when asked (after the
asker confirms), answers repeat questions in channels you opt in, flags answers that lean on old
documents, and reports what people ask (Insights, weekly digest). See [spec/roadmap.md](spec/roadmap.md).
The tests use fakes for every network edge, so these need real credentials to verify: Slack (events, scopes,
`conversations.info` replies, Add to Slack and Sign in with Slack), Anthropic, Google Drive and Gmail
(domain-wide delegation), Linear, Trello and Grain. The live checks under `src/test/java/com/docs0/live` do that.

## What you need

- Java 25
- PostgreSQL 14 or newer, running locally, with a database named `docs0`
  (`createdb docs0`; tests use `docs0_test`)
- An Anthropic API key
- A Slack workspace where you can create an app
- A Google account that can open the Drive folders you want indexed, and a Google Cloud
  project where you can create an OAuth client
- Optional: a Linear workspace and a Trello account (see steps 4 and 5)

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

If you created the app before feedback existed, paste the manifest again (*App Manifest*
in the app settings) so the app also subscribes to `reaction_removed`, `app_uninstalled` and `tokens_revoked`.
The manifest no longer asks for the `im:read` scope (docs0 knows a direct message from the event itself), so an
app that still has it can keep it or drop it.

docs0 connects out to Slack over Socket Mode, so it needs no public URL. (To serve several companies instead,
see [Running docs0 for several companies](#running-docs0-for-several-companies).)

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

### 4. Linear (optional)

Linear needs no OAuth client and no new environment variable, only the `DOCS0_ENCRYPTION_KEY`
from step 3. You paste a personal API key on the Sources page and docs0 stores it encrypted.

1. In Linear open **Settings → Account → Security & access** and, under **Personal API
   keys**, create a key. Copy it (it starts with `lin_api_`). The key can do what you can do
   in Linear, so use the account of someone who can see the teams you want indexed and who is
   allowed to create issues.
2. Open <http://localhost:8080/admin/sources>, find **Linear**, paste the key and click
   **Connect Linear**. docs0 tests the key with one call and stores it only if Linear accepts
   it. The page then shows the workspace name; the key is never shown again.
3. Tick the **teams** to include and click **Save**. Nothing is indexed until a team is
   ticked. **Check access** shows how many issues docs0 can read per team; **Sync now**
   starts a sync (otherwise it runs at startup and every 15 minutes).

Indexed: every non-archived issue of the chosen teams (status, assignee, labels, priority,
due date, project, cycle, description and the first 50 comments), the projects of those teams
and the Linear documents attached to those projects. **Disconnect** deletes the stored key and
removes Linear items from answers.

### 5. Trello (optional)

1. Open <https://trello.com/power-ups/admin>, choose **New** to make a Power-Up (any name),
   then open its **API key** page and copy the **API key**.
2. On that page choose to generate a **Token**, allow it, and copy it. It needs read **and
   write** access: docs0 adds a card only when someone asks and confirms, but Trello's token
   has no create-only scope.
3. On the Sources page, under **Trello**, paste the key and the token and click **Connect
   Trello**. docs0 tests them with one call, stores them encrypted, and shows the account.
4. Tick the **boards** to include and **Save**, then **Sync now**.

Indexed: for each chosen board a document with its description and its lists with card
counts, and one document per open card (list, labels, members, due date, description,
checklists with ✓ and ☐ items, and comments). docs0 stays under Trello's rate limit and backs
off when Trello asks it to.

### Grain (optional)

Grain needs no OAuth client, only the `DOCS0_ENCRYPTION_KEY` from step 3. docs0 reads your
meeting recordings (AI summary, notes, highlights and transcript) and never changes anything in
Grain.

1. In Grain open **Settings → Integrations → Public API** (<https://grain.com/app/settings/integrations?tab=api>).
   A workspace admin can create a **workspace access token**, which sees every recording in the
   workspace (not on every plan). Anyone can create a **personal access token** from their account
   integrations page; it sees only that person's recordings. Copy the token.
2. Open <http://localhost:8080/admin/sources>, find **Grain**, paste the token and click
   **Connect Grain**. docs0 tests it with one call and stores it only if Grain accepts it. The
   page then shows the account name; the token is never shown again.
3. **Check access** shows how many recordings docs0 can read; **Sync now** starts a sync
   (otherwise it runs at startup and every 15 minutes).

Only recordings from the last 365 days are indexed. Change that with `DOCS0_GRAIN_HISTORY_DAYS`
in `.env`; recordings that fall out of the window are removed from answers at the next sync.
Each recording is one document: date, duration, participants, summary and notes, then the
transcript with a time and speaker per line. A recording whose transcript is not ready yet is
indexed with what exists and refreshed at the next syncs. **Disconnect** deletes the stored token
and removes Grain recordings from answers.

### Slack history and private channels

docs0 can also learn from what your team says in Slack, so it can answer "what did we decide
about X?" from a channel discussion. Open **Sources → Slack history**, tick the channels to
include and press **Save**, then **Sync now**. Nothing is indexed until you tick at least one.

**What is indexed.** Only the channels you tick: one document per thread, and one document per
channel per day for messages that are not in a thread, for the last `DOCS0_SLACK_HISTORY_DAYS`
days (default 180). Joins, leaves, topic changes and docs0's own messages are left out. Edited
messages are re-read and deleted messages disappear at the next sync. A channel you untick (or
that docs0 can no longer see) has its documents removed at the next sync.

**What is never indexed.** Direct messages and group direct messages. docs0 does not read them
at all: it only ever asks Slack for the channels you ticked.

**How private channels are protected.** Public channels are shared knowledge. A private channel
can be ticked once docs0 has been invited to it (`/invite @docs0` in that channel), and its
messages are then **restricted**: docs0 only uses them in an answer when *everyone who will see
that answer* is a member of that private channel. Restricted documents are used only in direct messages
(the one person) and in private channels (every member of that channel); a **public channel** can be read by
anyone in the workspace, so it only ever gets shared knowledge, and so does any channel whose privacy Slack
cannot tell docs0 (docs0 asks Slack with `conversations.info`, which the existing `channels:read`,
`groups:read` and `mpim:read` scopes cover, and remembers the answer for a few minutes). If even one person is
outside the private channel, the messages are invisible to the answer: not quoted, not cited,
not even hinted at. Bots and deactivated users are not counted as people. Membership is
re-read on every sync, so someone who leaves a private channel stops being covered at the next
sync. If docs0 cannot tell who is in the channel it hides that channel's documents until it can.

**One-time step for the new permissions.** Slack history needs two new bot scopes,
`users:read.email` (to match people by email as well as by Slack id) and `channels:join` (to
join public channels you tick). If you created the app earlier:

1. Open your app at <https://api.slack.com/apps> → **App Manifest**, paste the current
   [slack/manifest.yml](slack/manifest.yml) and save.
2. **Reinstall the app** (*Install App → Reinstall to Workspace*). The bot token stays the same.

Until then Slack history still works for public channels and matches people by Slack id; the
Sources page shows a note that emails cannot be read. For each private channel, run
`/invite @docs0` in it; the Sources page shows "invite @docs0 to #name" while docs0 is not in
it.

### Gmail (optional)

docs0 can answer from your email, **with every mailbox private to its owner**. A Google Workspace
administrator connects everyone once (domain-wide delegation); docs0 indexes the mailboxes you choose.
A person's mail can appear in an answer only when everyone who will see that answer is entitled to it,
which in practice means **in that person's own direct message with docs0**. It is never used in a
channel, in a group chat or in anyone else's DM. Beyond the mailbox owner, you can list extra people
per mailbox ("also visible to") for a shared inbox such as support@: the mailbox's mail is then also
available in their DMs. **docs0 can read every mailbox you add**; it only ever reads (the Gmail
scope is `gmail.readonly`) and never sends, changes or deletes anything.

**What is indexed.** For each chosen mailbox, one document per conversation thread from the last
`DOCS0_GMAIL_HISTORY_DAYS` days (default 90): subject, then each message with From, To, Cc and Date
and its text (plain text preferred, HTML turned into text, quoted replies and short signatures
trimmed). Attachment file names are listed; attachments are never downloaded. **Left out:** Spam, Trash,
Drafts, and the Promotions, Social and Forums categories. A thread that is deleted, trashed, or older
than the window is removed at the next sync; docs0 re-reads a thread only when Gmail says it changed.

**Admin steps** (you must be a Workspace administrator; the Sources page repeats these):

1. In [Google Cloud Console](https://console.cloud.google.com/) make or pick a project and enable the
   **Gmail API** (**APIs & Services → Library**). If you want to pick people from your directory, also
   enable the **Admin SDK API**.
2. **IAM & Admin → Service Accounts → Create service account** (no roles needed). Open it, then
   **Keys → Add key → Create new key → JSON**. A file downloads.
3. Open **Sources → Gmail**, paste the whole contents of that file and press **Save key**. docs0
   checks it and stores it encrypted (it needs `DOCS0_ENCRYPTION_KEY`, like the other tools). The key
   is never shown again: the page shows only the service account's email and its **client ID**.
4. In the [Admin console](https://admin.google.com/ac/owl/domainwidedelegation) go to **Security →
   Access and data control → API controls → Domain-wide delegation → Add new**. Paste the client ID
   from the Sources page and the scope `https://www.googleapis.com/auth/gmail.readonly`. Only if you
   want the directory import, add `https://www.googleapis.com/auth/admin.directory.user.readonly`
   too (comma separated). Press **Authorise**.
5. Back on **Sources → Gmail**, choose the mailboxes: type email addresses, one per line, or give the
   email of an administrator and press **Load people from Google Workspace** and tick the people to
   include. Press **Save**. Optionally fill **Also visible to** for a shared inbox. Nothing is indexed
   until at least one mailbox is chosen.
6. Press **Check access**: each mailbox gets a ✓ with its message count, or the reason in plain words
   (delegation not authorised, Gmail API not enabled, person not found or suspended). Then **Sync now**.

Removing a mailbox or a viewer takes effect at the next sync (every few minutes, or **Sync now**).
**Disconnect** deletes the key and removes all mail from answers. Setting a wrong or revoked key fails
the run with a clear message and deletes nothing; a mailbox that cannot be read is skipped, shown with
its reason, and the others carry on.

**One thing people must know: Gmail matches a person to their Slack account by email, so the Slack
app needs the `users:read.email` scope (added in the Slack history step above) and people's Slack
email must be the same as their Gmail address.** Without it, or when someone's Slack email is hidden or
different, their mail is simply not shown to them (docs0 errs on the side of hiding).

**Admin pages never show private answers.** An answer that used a private source (a mailbox or a
private channel) is not shown on the Corrections page ("Unhappy answers", or the original answer of a
pending correction) or the Costs page: those show "This answer used a private source, so it isn't shown
here." instead of its text, sources and quotes.

### Creating issues and cards

docs0 reads from Linear and Trello and never edits or deletes anything there. The one thing
it writes is a **new** issue or card, and only like this:

1. Someone asks in a thread, for example "@docs0 file a Linear issue for the Safari login bug,
   high priority". docs0 only drafts when it is asked in the conversation; it never files on
   its own.
2. docs0 answers with what it drafted, and posts the **draft** in the thread: kind, where it
   will go (Linear team, or Trello board and list), title and description, with **Create** and
   **Cancel** buttons. Nothing exists in Linear or Trello yet.
3. Only the person who asked can click. **Create** makes the item once (a double click makes
   one) and replaces the draft with a link to it. **Cancel** drops it. A draft not confirmed
   within an hour expires.

The tools to draft are offered to the model only when that tool is connected and has a team
or board chosen. If a create fails, docs0 says so and records the error; it does not retry.

### Repeat-question answers, stale documents and Insights (Phase 6)

Everything here is off or silent until you turn it on, and none of it needs a new key. The new
settings are optional:

| Variable | Default | What it does |
| --- | --- | --- |
| `DOCS0_PROACTIVE_MAX_PER_HOUR` | `3` | Most unprompted answers per opted-in channel per hour. |
| `DOCS0_STALE_DAYS` | `365` | An answer that cites a document last updated longer ago than this says it may be out of date. |
| `DOCS0_DIGEST_CRON` | `0 0 9 * * MON` | When the weekly digest is posted (Spring cron: second minute hour day month weekday). `-` turns the schedule off. |
| `DOCS0_TIMEZONE` | `UTC` | Time zone of that schedule, for example `Europe/London`. |

**One-time step if you created the Slack app before Phase 6.** Asking a document's owner to review
it opens a direct message with them, which needs the `im:write` bot scope (added to
[slack/manifest.yml](slack/manifest.yml)). Paste the current manifest again (*App Manifest* in the
app settings, save) and **reinstall the app** (*Install App → Reinstall to Workspace*); the bot token
stays the same. Until then everything else works and only the "Ask the owner to review" button says
that Slack did not accept the message.

## Run

```
./mvnw spring-boot:run
```

Open <http://localhost:8080> for the status page: connection state for the Database, Slack, Anthropic,
Google Drive, Gmail, Linear, Trello, Grain and Slack history, the document count, the last sync and this month's spend, with a link to the
Sources page. `/health` returns the same as JSON; when `DOCS0_ADMIN_PASSWORD` is set the status page asks for
that password (user `admin`) and `/health` returns only `{"status":"UP"}`. Without `DOCS0_ADMIN_PASSWORD` the admin pages are served
only to requests from this machine **for a `localhost`, `127.0.0.1` or `[::1]` host name** (any other `Host`
gets 403); set `DOCS0_ADMIN_PASSWORD` if you reach docs0 by another name or through a proxy. The app starts even when a credential is missing; the status page says
what is not set up.

### Multiple companies

docs0's data is organised per company (tenant), and the code no longer assumes there is only one: admin pages,
Slack events and scheduled jobs each work for an explicitly named company, and a second company's documents,
corrections, spend and Slack workspace can never reach the first. **The pilot setup above is unchanged**: one
tenant created from `DOCS0_TENANT_SLUG`/`DOCS0_TENANT_NAME`, one admin password, Slack over Socket Mode with the
tokens from `.env`. At startup docs0 registers that Slack workspace for the tenant (it stores the bot token
encrypted, so `DOCS0_ENCRYPTION_KEY` must be set). Leave the three `DOCS0_SLACK_CLIENT_*`/`SIGNING_SECRET`
settings blank and nothing below applies.

### Running docs0 for several companies

Socket Mode is for a single-company pilot. To let other companies add docs0 to their own Slack and manage their
own docs, run it as a distributed Slack app:

1. **Create the Slack app** from [slack/manifest-distributed.yml](slack/manifest-distributed.yml) (replace
   `YOUR-DOMAIN` with your host first). It has the same scopes and events as the pilot manifest, but Slack calls
   docs0 over HTTPS (`/slack/events`, `/slack/interactions`) instead of Socket Mode. Under *Manage Distribution*
   activate public distribution. "Sign in with Slack" needs no scope line in the manifest, only the redirect URL
   it already lists (`/admin/login/callback`).
2. **Set three settings** from *Basic Information → App Credentials*: `DOCS0_SLACK_CLIENT_ID`,
   `DOCS0_SLACK_CLIENT_SECRET` and `DOCS0_SLACK_SIGNING_SECRET`. With all three set, docs0 is in multi-tenant mode.
   Also set `DOCS0_ENCRYPTION_KEY` (bot tokens are stored encrypted).
3. **Use a public https URL.** `DOCS0_PUBLIC_URL` must be the public `https://` address Slack reaches (a reverse
   proxy with TLS in front of docs0 is fine). docs0 logs a warning at startup when it is not https. Admin session
   cookies are `HttpOnly`, `SameSite=Lax` and `Secure` when the URL is https.
4. **What happens on install.** The landing page (`/`) has an **Add to Slack** button. When a workspace installs
   docs0, a company is created for it (its name is made URL-safe and unique, for example `acme`, `acme-2`), the bot
   token is stored encrypted, the installer becomes the company's first admin (by email, when Slack lets docs0
   read it) and gets a welcome message with a link to the Start page. Installing again renews the token and keeps
   the company's data; a suspended company stays suspended. If a workspace removes docs0 (Slack's `app_uninstalled`
   or `tokens_revoked` events, which the app must be subscribed to), docs0 stops serving it but keeps its data.
5. **Signing in.** Admins click **Sign in** (`/admin/login`) and use Sign in with Slack. They can enter their own
   company only, if their email is on that company's admin list or Slack says they are a workspace admin or owner.
   The **Start** page (`/admin/start`) is a checklist for the company (Slack connected, add knowledge, invite
   docs0 to channels, try it) and where admins manage that list (up to 20 emails). A session ends after 8 hours idle or 12 hours after sign-in, and is checked again against the admin list and
   Slack at most every 5 minutes: an admin who is removed from the list (or stops being a workspace admin) loses
   access within about 5 minutes. If Slack cannot be reached the last good answer holds for 15 more minutes,
   then the session ends. Install and sign-in pages allow 30 requests a minute per address.
   In this mode the "local machine only" shortcut for `/admin` is off; the operator can still reach the admin
   pages for the configured tenant with `DOCS0_ADMIN_PASSWORD` over HTTP Basic.
6. **Budget per company.** A company that installs itself starts with its own cap of `DOCS0_NEW_TENANT_BUDGET_USD`
   (default 5 USD a month, so a new install is never unmetered); the pilot company, created from
   `DOCS0_TENANT_SLUG`, uses the global `DOCS0_MONTHLY_BUDGET_USD`. A company's own cap in the database wins:
   `NULL` means "use `DOCS0_MONTHLY_BUDGET_USD`", any number is the cap in USD, and **`0` means no budget at all,
   every question is refused**. The operator raises a company's cap in the database:
   `UPDATE tenant SET monthly_budget_usd = 25 WHERE slug = 'acme';` (`NULL` goes back to the default). To suspend a company: `UPDATE tenant SET status = 'SUSPENDED' WHERE slug = ...`
   (its admins can still sign in but only see a "suspended" page; set `'ACTIVE'` to lift it).
7. **Google Drive caveat.** All companies connect Google Drive through the one Google OAuth client you configured
   (`DOCS0_GOOGLE_OAUTH_CLIENT_ID`). For other companies' admins to sign in with it, that client must be set up as
   an **External** app and pass Google's OAuth verification (Drive read-only is a restricted scope). With an
   **Internal** client only people in your own Google Workspace domain can connect Drive. Linear, Trello and Grain
   are connected with each company's own keys and are unaffected.
8. **Isolation.** Every table carries the company's id, every query filters on it, and the database refuses a row
   that points at another company's row. An admin's session holds the one company they signed in to; no URL,
   form field or header can change it. Each company has its own Slack workspace and bot token, its own sources and
   credentials, corrections, conversations, budget and admin list.

Not built: per-company Anthropic keys (your key is shared, and so is the model bill), billing or plans,
organization-wide Slack Enterprise Grid installs, and a super-admin console (use SQL).

## Use

- Mention it in a channel: `@docs0 what is our refund policy?` It answers in a thread.
- Reply in that thread to ask a follow-up. No mention needed.
- Send it a direct message for a private conversation.
- React 👍 or 👎 to an answer. On 👎 docs0 asks what was wrong; reply in the thread with
  the right answer (or just reply "that's wrong, it's actually…" at any time) and it goes
  to the admins as a pending correction. It changes nothing until an admin approves it.
- Admins review corrections on the **Corrections** page (`/admin/corrections`): approve
  (optionally after editing the wording), reject, or un-approve later. The same page lists
  answers that got a 👎. The **Costs** page (`/admin/costs`) shows spend per day and the
  most expensive questions.
- Approved corrections outrank documents. When one shapes an answer, docs0 says so, for
  example "per Arjun's correction on 2 Nov". A correction made in a DM is credited to
  "a teammate", never by name.

- Ask what people said: Slack threads in the channels you chose are searched together with the
  documents. A private channel's messages only appear in answers shown to people who are all in it.

- Ask about work in progress too: "what is the status of ENG-12?", "what is on the roadmap
  board?". Linear issues and Trello cards are searched together with the documents.

Each answer lists its sources with the exact passage it relied on. When docs0 is unsure
it says so, asks a clarifying question, or names who might know.

- **Repeat questions.** On the **Channels** page (`/admin/channels`) tick the channels where docs0
  may answer without being asked. Nothing is ticked by default. In a ticked channel, when someone
  posts a question that docs0 has already answered in a channel before, docs0 replies in a thread on
  that message with "This looks like something we've covered before. Here's what I found:" and a fresh
  answer from your current sources and approved corrections (never a copy of the old one). It speaks up
  at most `DOCS0_PROACTIVE_MAX_PER_HOUR` times an hour per channel, with a two-minute pause between
  answers, never in a direct message, never on a message that already has replies, and only when it is
  confident. It uses the same rules about who may see which documents as an @mention. A 👎 on one of
  these answers counts like any other and keeps docs0 quiet in that channel for a day. Questions asked in
  direct messages are never used to recognise repeats.
- **Old documents.** When an answer cites a document last updated more than `DOCS0_STALE_DAYS` days ago,
  a line under the sources says "Based on a document last updated March 2023. It may be out of date."
- **Insights.** The **Insights** page (`/admin/insights`, 7, 30 or 90 days) shows totals (questions,
  👍 and 👎, how many repeat answers were offered, spend), the top question **themes** (one AI call,
  kept for six hours; **Refresh themes** repeats it at most every ten minutes), **knowledge gaps**
  (questions with no answer, low confidence or a 👎, with how often a similar one came up), the old
  documents people rely on, and how repeat answers were received. For each old document, **Ask the
  owner to review** sends the owner (found in Slack by email) a short direct message, at most once
  per document in 14 days; docs0 never does this by itself. Direct-message and private-channel questions (and any whose channel privacy is unknown) are only counted,
  never shown, quoted or sent to the themes model, and a question whose answer used a private source (a private channel, a
  mailbox) is shown as "Question not shown (private source)".
- **Weekly digest.** On the Insights page choose a channel (and invite `@docs0` to it); docs0 then posts
  a short summary of the last 7 days there on `DOCS0_DIGEST_CRON`, once a week, and **Post digest now**
  sends one immediately. Nothing is posted until a channel is chosen, or when this month's budget is used
  up. The digest never contains direct-message questions, who asked, private-source content or
  questions from private channels. The AI calls behind repeat-question checks and themes are billed to the
  monthly cap too; the **Costs** page shows them on their own line.

## Deploying to Cloud Run

docs0 can run on Google Cloud Run in the project `ai-agent-boilerplate0` (region `us-central1`), sharing the existing
Cloud SQL Postgres instance `ai-agent-db` with the other apps. Everything is driven by `deploy/deploy.sh` (a dry run
unless you pass `--apply`) and the GitHub Actions workflow `.github/workflows/deploy.yml`.

> **Status.** Everything described here is implemented and tested in this repository: the `Dockerfile`, `PORT`
> handling, the small database pool, database auto-creation, the password-protected status page, the HTTP transport
> for the single-company Slack app (`DOCS0_SLACK_TRANSPORT=http`) with the request held open while an answer is
> produced, the job entrypoint (`--docs0.job=sync` / `--docs0.job=digest`), `DOCS0_INTERNAL_SCHEDULER=false`, the
> "Sync now" button that starts the sync job (`DOCS0_SYNC_JOB_NAME`, `DOCS0_GCP_PROJECT`, `DOCS0_GCP_REGION`),
> `deploy/deploy.sh` and the workflow. What can only be checked on the real cloud (metadata-server token, the Cloud Run
> Admin API call, real cold-start timing and Slack's retry behaviour) is listed in the build journal.

### How it runs

- **Scale to zero.** One Cloud Run service `docs0` (`--min-instances 0 --max-instances 1`, 1 vCPU, 1 GiB, request-based
  CPU). When nobody uses it there is no instance and nothing to pay for. With request-based CPU the container gets
  almost no CPU whenever no request is in flight, so docs0 never does work after a response (see "Hold-open" below).
- **Cold start.** The first request after idle starts a container (about 10 seconds plus the first-boot migrations only
  once). A Slack message right after a quiet spell is answered a few seconds late. The first event often reaches the
  new instance before its background Slack registration has finished: docs0 then registers the workspace on the spot
  (one `auth.test` call, shared by all events that arrive meanwhile) instead of dropping the message.
- **Hold-open.** Slack wants an answer within 3 seconds, but an answer takes longer, and anything left running after the
  response is frozen by Cloud Run. So with `DOCS0_SLACK_TRANSPORT=http` the `/slack/events` and `/slack/interactions`
  requests are held open until the work (the answer, a follow-up, a thumbs-down prompt, a draft button) is finished, at
  most `DOCS0_SLACK_HOLD_OPEN_SECONDS` (default 270, under Cloud Run's 300 s request timeout), and then answered 200 with an
  empty body. Slack gives up waiting after 3 seconds and sends the same event again with `X-Slack-Retry-Num`; docs0
  answers every retry, and any event it already has in hand, with an immediate 200 and does not run it twice. The
  held request keeps running after Slack hangs up, so the answer is still posted. Slack may show "failed to deliver"
  or "your app didn't respond in time" for the first attempt in its event log; that is expected and harmless.
- **Slack over HTTP, not Socket Mode.** A scaled-to-zero service cannot hold a websocket, so Slack sends events to
  `$PUBLIC_URL/slack/events` and button clicks to `$PUBLIC_URL/slack/interactions`.
- **Background work is a Cloud Run Job, not a thread.** Document syncs and the weekly digest run as the Cloud Run Jobs
  `docs0-sync` and `docs0-digest` (the same image, started with `--docs0.job=sync` / `--docs0.job=digest`), triggered by
  Cloud Scheduler: `docs0-sync` every 15 minutes (`*/15 * * * *`), `docs0-digest` on Monday 09:00 (`0 9 * * 1`, in
  `DOCS0_TIMEZONE`, default UTC). Scheduler calls the Cloud Run Jobs `run` API with an OAuth token of the runtime
  service account. The in-app scheduler is off in the service (`DOCS0_INTERNAL_SCHEDULER=false`): no timer runs in the
  service at all. A job starts the same image without a web server and without Slack listeners, registers the Slack
  workspace from `DOCS0_SLACK_BOT_TOKEN`, does its work and exits (0 = it ran; a source that failed is recorded in the
  sync log and does not fail the job; non-zero = the job itself crashed). `docs0-sync` syncs every configured source of
  every active company; `docs0-digest` posts the weekly digest once per company per week. Two syncs of one source (two
  job runs, or a job and the service) are kept apart by a Postgres advisory lock; the loser logs "skipped" and stops.
- **Sync now.** With `DOCS0_SYNC_JOB_NAME` set, the Sources page "Sync now" buttons (Drive, Linear, Trello, Grain, Gmail,
  Slack history) start `docs0-sync` for that one source through the Cloud Run Admin API (the service account needs
  `run.jobs.runWithOverrides`; the `jobs` step of the deploy script grants `roles/run.developer` on `docs0-sync`, because
  `roles/run.invoker` alone does not cover per-run arguments). The page says "A sync was requested. It starts in a few seconds; this page refreshes while it runs." and
  the run appears when the job starts; if the request fails it says "Could not start the sync job (...). It will run at
  the next scheduled time." The "is a sync running" state on the pages is read from the database, so it is right
  while a job in another process is syncing; a run marked RUNNING for over 3 hours shows as Interrupted.
- **Shared database.** The instance `ai-agent-db` (Postgres 17, private IP `10.110.112.7`, SSL required) holds about ten
  apps and allows 90 connections in all, so docs0 keeps a pool of at most 5 (`DOCS0_DB_POOL_MAX`). docs0 gets its own
  role `docs0_app` (created with `gcloud sql users create`, so it may `CREATE DATABASE`) and its own database `docs0`,
  which the app creates itself on first boot (`DOCS0_AUTO_CREATE_DATABASE=true`: it connects to the `postgres`
  database as that role, takes an advisory lock and runs `CREATE DATABASE "docs0"` if it is missing, so the role owns
  it). Cloud Run reaches the private IP through Direct VPC egress (`--network default --subnet default --vpc-egress
  private-ranges-only`). The URL is `jdbc:postgresql://10.110.112.7:5432/docs0?sslmode=require`.
- **Admin access.** In hosted mode `DOCS0_ADMIN_PASSWORD` is required. `/admin/**` and the status page `/` need HTTP
  Basic (user `admin`); `/health` is public and returns only `{"status":"UP"}`. Loopback admin access does not apply on
  Cloud Run.
- **Configuration is plain environment variables.** There is no Secret Manager. Settings and secrets live in the GitHub
  environment `production` and are written as environment variables on the Cloud Run service and jobs. Anyone who can
  view the service or job configuration in the Cloud console can read them, so keep project access small.
- **Cost.** A few US dollars a month at current list prices (mostly Cloud Scheduler and the job runtime every 15
  minutes; the service itself is free while idle). Check the Cloud Run pricing page.
- **No data migrates from your laptop.** The hosted database `docs0` starts empty; documents re-sync from Drive after
  you connect Google Drive again on the hosted Sources page.

### One-time setup

1. **GitHub environment.** Repository Settings -> Environments -> New environment `production`. Add the secrets and
   variables below yourself in the GitHub page (Add environment secret / Add environment variable). Never paste a secret
   into a chat or an issue.

   | Name | Kind | Purpose |
   |---|---|---|
   | `DOCS0_ANTHROPIC_API_KEY` | secret | Anthropic API key |
   | `DOCS0_SLACK_BOT_TOKEN` | secret | Slack bot token (`xoxb-...`) |
   | `DOCS0_SLACK_SIGNING_SECRET` | secret | Slack Basic Information -> Signing Secret; verifies events |
   | `DOCS0_ENCRYPTION_KEY` | secret | base64 of 32 random bytes (`openssl rand -base64 32`); encrypts stored Google grants. Back it up |
   | `DOCS0_ADMIN_PASSWORD` | secret | password for `/admin` and `/` (user `admin`) |
   | `DOCS0_DATABASE_PASSWORD` | secret | password of role `docs0_app`; generate once: `openssl rand -base64 24 \| tr -d '/+=\n'` |
   | `DOCS0_GOOGLE_OAUTH_CLIENT_ID`, `DOCS0_GOOGLE_OAUTH_CLIENT_SECRET` | secret | Google OAuth web client for Drive sign-in |
   | `DOCS0_CRON_TOKEN` | secret, optional | reserved; jobs replace internal endpoints so nothing needs it yet |
   | `DOCS0_PUBLIC_URL` | variable, optional | default `https://docs0.smalltech.in` |
   | `DOCS0_TENANT_SLUG`, `DOCS0_TENANT_NAME` | variable, optional | default `smalltech`, `smallTech` |
   | `DOCS0_MONTHLY_BUDGET_USD`, `DOCS0_ANTHROPIC_MODEL` | variable, optional | default `100`, `claude-sonnet-5-5` |
   | `DOCS0_SLACK_HISTORY_DAYS`, `DOCS0_GRAIN_HISTORY_DAYS`, `DOCS0_GMAIL_HISTORY_DAYS`, `DOCS0_PROACTIVE_MAX_PER_HOUR`, `DOCS0_STALE_DAYS`, `DOCS0_AGENT_MAX_STEPS`, `DOCS0_NEW_TENANT_BUDGET_USD`, `DOCS0_TIMEZONE`, `DOCS0_DB_POOL_MAX` | variable, optional | same meaning as in [.env.example](.env.example); unset = app default |

   `DOCS0_SLACK_APP_TOKEN` is not needed in HTTP mode.
2. **Trust GitHub (once, by someone who can administer IAM):** `deploy/deploy.sh --step wif` to review, then
   `deploy/deploy.sh --apply --step wif`. It creates the Workload Identity provider `docs0-github` (pool `github`),
   restricted to `smallTechOrg/docs0`, and lets the existing deployer service account be impersonated from it.
3. **Create the role (once):** with `DOCS0_DATABASE_PASSWORD` exported in your shell,
   `deploy/deploy.sh --apply --step db-user`. Re-running sets the same password again.
4. **First deploy, by hand or from the workflow.** Stop the laptop docs0 first (see below). Review the plan with a dry
   run, then apply: Actions -> Deploy -> Run workflow (tick *Dry run* first; the default steps are `deploy jobs`), or
   locally with the variables exported: `deploy/deploy.sh`, then `deploy/deploy.sh --apply`. The first boot creates
   the database and runs the migrations (allow up to 3 minutes; the startup probe waits for `/health`).
5. **Scheduler (once, and when a schedule changes):** `deploy/deploy.sh --apply --step scheduler` (enables
   `run.googleapis.com` and `cloudscheduler.googleapis.com`, creates/updates `docs0-sync` and `docs0-digest`).
   Run the workflow with `steps` = `deploy jobs scheduler` if the deployer account may do this.
6. **Domain (once):** `deploy/deploy.sh --apply --step domain` creates the Cloud Run domain mapping for
   `docs0.smalltech.in` and the step prints the DNS record to add: `CNAME docs0 -> ghs.googlehosted.com.` Until DNS
   and the certificate are ready, use `https://docs0-870371939888.us-central1.run.app`.

### Slack app changes (the owner makes these in api.slack.com)

Edit the same Slack app you already use for the laptop version, once the service is live:

1. Open https://api.slack.com/apps, choose the app, then **App Manifest**. Paste the contents of
   [slack/manifest-hosted.yml](slack/manifest-hosted.yml) (same scopes and bot events as `slack/manifest.yml`; it turns
   Socket Mode off, sets the Event Subscriptions Request URL to `https://docs0.smalltech.in/slack/events` and the
   Interactivity Request URL to `https://docs0.smalltech.in/slack/interactions`) and save. If the service is not on
   `docs0.smalltech.in` yet, change both URLs in the pasted text.
   *By hand instead:* Settings -> Socket Mode -> off; Event Subscriptions -> on, Request URL
   `https://docs0.smalltech.in/slack/events` (it must show *Verified*, so the service has to be deployed with
   `DOCS0_SLACK_SIGNING_SECRET` set), keep the existing bot events; Interactivity & Shortcuts -> on, Request URL
   `https://docs0.smalltech.in/slack/interactions`.
2. **Basic Information -> App Credentials -> Signing Secret:** copy it into the GitHub secret
   `DOCS0_SLACK_SIGNING_SECRET`, then deploy again. (The Request URL check in step 1 only passes once the service runs
   with that secret.)
3. **Install App -> Reinstall to Workspace** so the changed settings apply. The bot token stays the same; no app-level
   token (`xapp-`) is needed any more.
4. **Stop the laptop instance** (below).

**Stop the laptop first.** Once the app uses HTTP events, a docs0 on your laptop in Socket Mode no longer receives
anything; do not run it against the same Slack app. Two docs0 instances against one workspace would split events.

### Google Drive redirect URI

On the Google OAuth client add the authorised redirect URI `$DOCS0_PUBLIC_URL/admin/sources/google/callback`
(`https://docs0.smalltech.in/admin/sources/google/callback`; add the `run.app` URL too if you use it before DNS), then
open `/admin/sources` on the hosted URL and connect Google Drive.

### Checking, rollback

- `deploy/deploy.sh --step verify` (read-only): service URL, `curl /health` (expect `{"status":"UP"}`; the first call
  after idle takes a few seconds), the Scheduler jobs.
- Log in at `/admin` as user `admin` with the value of the GitHub secret `DOCS0_ADMIN_PASSWORD`.
- Rollback: `deploy/deploy.sh rollback` lists revisions; `deploy/deploy.sh --apply rollback <REVISION>` sends all traffic
  to one. The database is not rolled back (migrations only move forward), and the jobs keep the image they were last
  deployed with until `deploy/deploy.sh --apply --step jobs`.
- Local build: the image needs no `.env`; `.dockerignore` and `.gcloudignore` keep `.env`, `.git`, `.claude` and
  `target` out of the upload.

### Variables the hosted setup uses

`PORT` (set by Cloud Run; overrides `DOCS0_PORT`), `DOCS0_DATABASE_URL` (use `?sslmode=require`),
`DOCS0_DATABASE_USER`, `DOCS0_DATABASE_PASSWORD`, `DOCS0_AUTO_CREATE_DATABASE` (default `false`), `DOCS0_DB_POOL_MAX`
(default 5), `DOCS0_ADMIN_PASSWORD`, `DOCS0_SLACK_TRANSPORT` (`socket` default, `http` hosted),
`DOCS0_SLACK_SIGNING_SECRET`, `DOCS0_SLACK_HOLD_OPEN_SECONDS` (default 270), `DOCS0_INTERNAL_SCHEDULER` (default
`true`; `false` hosted), `DOCS0_SYNC_JOB_NAME`, `DOCS0_GCP_PROJECT`, `DOCS0_GCP_REGION` (the last three: "Sync now"
starts the job). With none of the new settings set, docs0 behaves exactly as on a laptop: Socket Mode, in-process
timers, in-process "Sync now".

### Running a job by hand

`java -jar docs0.jar --docs0.job=sync` (optionally `--docs0.job.tenant=<id> --docs0.job.source=<id>`) or
`--docs0.job=digest` starts docs0 without a web server, runs once and exits with 0 (ran), 1 (crashed) or 2 (bad
arguments). It needs the same database settings as the service. Cloud Run Jobs start it this way.

## Test

```
./mvnw verify
```

Tests run against the real `docs0_test` Postgres database on localhost (set `DOCS0_TEST_DATABASE_URL` to use
another database or host; it defaults to `jdbc:postgresql://localhost:5432/docs0_test`). They never use real Slack,
Drive or Anthropic credentials. `com.docs0.isolation` holds the two-company tests that prove one company
never sees or changes the other's data.

## Design

- [spec/brief.md](spec/brief.md) — what docs0 is and the decisions behind it
- [spec/architecture.md](spec/architecture.md) — stack, layout, data model, access model
- [spec/agent.md](spec/agent.md) — how the agent searches, answers and verifies quotes
- [spec/capabilities.md](spec/capabilities.md) — behaviour, capability by capability
- [spec/roadmap.md](spec/roadmap.md) — phases
