# docs0

A knowledge agent for your company's docs and tools. The team asks it questions in Slack;
it answers from the company's own content with source links and quoted snippets.

docs0 does not use vector search or embeddings. Claude searches a keyword index, opens
whole documents, and answers only from what it read. Every quote in an answer is checked
against the source document before it is shown.

**Status:** Phase 5 — answers from Google Drive, Linear, Trello, Grain, Slack history and Gmail
(each mailbox private to its owner), and can file a Linear issue or Trello card when asked (after the
asker confirms). See [spec/roadmap.md](spec/roadmap.md).

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
in the app settings) so the app also subscribes to `reaction_removed`.

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
that answer* is a member of that private channel. In a channel, that is every member of the
channel docs0 is answering in; in a direct message it is the one person. If even one person is
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

## Run

```
./mvnw spring-boot:run
```

Open <http://localhost:8080> for the status page: connection state for Slack, Anthropic,
Drive, Linear and Trello, the document count, the last sync and this month's spend, with a link to the
Sources page. `/health` returns the same as JSON. The app starts even when a credential is missing; the status page says
what is not set up.

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
