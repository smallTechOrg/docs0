# Capabilities

Each capability lists its behaviour and the phase that delivers it (see `roadmap.md`).

## C1. Slack conversation (Phase 1)

- **Triggers.** An `app_mention` in a channel docs0 is in. Any message in a DM with
  docs0. Any reply in a thread where docs0 has already answered (no @mention needed).
- **Ignored.** Messages from bots (including docs0), message edits and deletions, channel
  joins, and thread replies in threads docs0 has not answered.
- **Threading.** A channel answer is always posted in the thread of the triggering
  message (a new thread if the mention is top-level). A DM answer is posted in the DM,
  unthreaded, unless the user wrote inside a DM thread.
- **Status line.** docs0 immediately posts a status message ("Searching your docs…"), updates
  it in place as tools run ("Reading *Pricing 2026*…"), then replaces it with the answer.
  Updates are throttled to one per second. Everything in a status line (titles, search words) is escaped for
  Slack and titles and queries are cut to 80 characters.
- **Answer layout.** Answer text with `[n]` markers; a Sources section listing each
  cited document as a numbered link with its quoted snippet as a block quote; for low
  confidence a visible "Low confidence" note; suggested people when present; up to three
  follow-up questions as buttons. Clicking a follow-up button asks that question in the
  thread on behalf of the clicker.
- **Clarify / unknown.** A clarifying question is posted as plain text; the user's next
  reply in the thread continues the conversation. "I don't know" names what was searched.
- **Empty mention.** "@docs0" with no text gets a one-line hint on how to ask.
- **Failures.** If answering fails, the status line becomes a short apology; nothing is
  left spinning. If the knowledge base is empty, docs0 says no sources are synced yet.
- **Concurrency.** Questions are handled off the Slack event thread; the event is
  acknowledged within 3 seconds. Duplicate event deliveries are de-duplicated.
- **Logging.** Every question and answer is stored as an interaction with who asked,
  where, citations, token usage, cost and duration.

## C2. Google Drive knowledge (Phase 1)

There is no service account. An admin connects Drive from the dashboard.

- **Connect with Google.** On the Sources page the admin clicks *Connect Google Drive*,
  signs in with Google and grants read-only Drive access. docs0 stores the resulting
  refresh token encrypted, per tenant, and shows which Google account is connected.
- **Choose what to index.** After connecting, the admin browses their Drive on the
  Sources page — My Drive folders, folders shared with them, and shared drives — opens
  folders to go deeper, and ticks the folders to index. Everything inside a chosen
  folder is included, recursively. Nothing is indexed until at least one folder is
  chosen. The selection can be changed at any time.
- **Check access.** For the current selection the page shows each folder's name and how
  many indexable files docs0 found in it, without waiting for a sync.
- **Sync now.** A button starts a sync immediately; the page shows it running and then
  the result. Syncs also run at startup and every 15 minutes.
- **Disconnect.** Removes the stored grant and marks the source's documents deleted so
  they drop out of answers.
- **Types.** Google Docs, Sheets and Slides (exported to text / CSV), PDFs, and plain
  text / Markdown / CSV files. Other types are counted as skipped. Files over 20 MB and
  trashed files are skipped. Folders are traversed, not indexed.
- **Sync behaviour.** Unchanged files (same modified time) are not re-downloaded. Files
  no longer inside a chosen folder are marked deleted and drop out of search. One failed
  file never aborts a run. If Google rejects the stored grant (revoked or expired), the
  run fails with a clear "reconnect Google Drive" message and nothing is deleted.
- **Sync log.** Each run records counts (added, updated, removed, skipped, failed) and
  any error. Recent runs are listed on the Sources page.
- **Stored per document.** Title, type, link, owner name and email, modified time, full
  extracted text.
- **Setup needed once per deployment.** A Google OAuth client (`DOCS0_GOOGLE_OAUTH_CLIENT_ID`,
  `DOCS0_GOOGLE_OAUTH_CLIENT_SECRET`) with redirect URI
  `<DOCS0_PUBLIC_URL>/admin/sources/google/callback`, and `DOCS0_ENCRYPTION_KEY`. When
  any is missing the Sources page explains what to set instead of showing the button.
- **Admin access.** Pages under `/admin` require HTTP Basic auth with
  `DOCS0_ADMIN_PASSWORD` (user `admin`). When no password is set they are served only
  to requests from the local machine.

## C3. Cited answering (Phase 1)

Defined in `agent.md`: agentic search, verified quotes, confidence, follow-ups,
clarifying questions, honest "I don't know", suggested people, conflict handling.

## C4. Status page (Phase 1)

`/` shows: a link to the Sources page; connection state, with the reason when one is off, for every component: Database, Slack, Anthropic, Google Drive, Gmail, Linear, Trello, Grain and Slack history;
document count; last sync run; number of questions answered. `/health` returns JSON
`{"status":"UP", ...}` with the same component states. No secrets are shown.

## C5. Feedback and corrections (Phase 2)

- 👍 and 👎 reactions on a docs0 answer are recorded per user (a changed reaction updates
  the row, a removed one deletes it). Reactions on other messages, other emoji and docs0's
  own reactions are ignored. The first 👎 on an answer makes docs0 ask in thread what was
  wrong; it asks once per answer and then waits up to 24 hours for the reply.
- A thread (or DM) reply that corrects docs0 is stored as a pending correction with author,
  the question that was answered, docs0's answer and the corrected fact. A reply is checked
  only when a "what was wrong?" prompt is waiting or it contains a phrase like "that's
  wrong", "actually," or "should be"; one small model call then decides correction or
  question. A question, or a failed call, is answered normally. docs0 thanks the author and
  says an admin will review it.
- An admin approves (optionally editing the wording), rejects or later un-approves
  corrections on the dashboard. Only approved corrections influence answers.
- Before answering, up to three approved corrections that share keywords with the question
  are put in front of the model; they outrank documents and are recorded on the
  interaction. An answer shaped by one says so: "per Arjun's correction on 2 Nov". A
  correction made in a DM is credited to "a teammate" and its question is never passed on.

## C6. Admin dashboard (Phase 2, grows each phase)

Protected by an admin password. A shared top navigation links the pages: Sources (sources
and sync log), Corrections (pending corrections, unhappy answers, reviewed corrections) and
Costs (this month against the cap, a table per day for 30 days, the ten most expensive
questions this month). Questions asked in a DM are shown anonymously in the unhappy
answers list and on the Costs page. The status page shows how many corrections are pending.
(Later: insights.)

Connecting Google Drive from the dashboard is delivered in Phase 1 (C2); Linear and Trello
(Phase 3, C7) have their own sections on the same page, and the page shows document counts per
source kind. Later sources get the same connect, check access and sync-now treatment.

## C7. Linear and Trello (Phase 3)

Linear issues, projects and documents and Trello boards and cards are shared knowledge, in
the same index as Drive. docs0 can also create one new Linear issue or Trello card from a
Slack thread, only after the asker confirms. It never edits or deletes anything in either tool.

- **Connect.** On the Sources page the admin pastes a Linear personal API key (`lin_api_…`)
  or a Trello API key and token. docs0 tests them with one call and stores them only if the
  tool accepts them, AES-256-GCM encrypted per tenant (`DOCS0_ENCRYPTION_KEY`) in
  `source_credential.encrypted_secret`. They are never logged or shown again; the page shows
  that a key is set and the workspace or account name the test call returned. No new
  environment variable is needed.
- **Choose.** Teams (Linear) or boards (Trello) are ticked from a list fetched with the
  credentials and saved in `source.config` as `{"teams":[{id,key,name}]}` or
  `{"boards":[{id,name}]}`. Nothing is indexed until at least one is chosen; clearing the
  selection removes what was indexed.
- **Check access, Sync now, Disconnect** work as for Drive: counts of issues or cards found
  for the selection (capped at 2,000), an asynchronous sync, and a disconnect that deletes the
  secret and marks the source's documents deleted. The last five sync runs are listed.
  Syncs also run at startup and every 15 minutes. Document counts per source kind are shown
  on the Sources page.
- **Documents.** One document per Linear issue (`issue:<id>`, title `ENG-12: <title>`),
  project (`project:<id>`) and Linear document (`doc:<id>`, only those attached to a project
  of a chosen team); one per Trello card (`card:<id>`) and per board (`board:<id>`, with its
  description and its lists with card counts). The text starts with labelled fields (Status,
  Assignee, Project, Labels, Priority, Due; for cards Board, List, Labels, Members, Due) and
  continues with the description, checklists (✓ / ☐) and comments. Owner is the assignee
  (else creator or lead) or the first card member; modified time is `updatedAt` or
  `dateLastActivity`. Unchanged items are not rewritten.
- **Failures.** A rejected key or token fails the run with "Linear rejected the API key.
  Reconnect Linear on the Sources page." or "Trello rejected the key or token. Reconnect
  Trello on the Sources page." and deletes nothing. Rate limits are waited out a bounded
  number of times, then the run fails and deletes nothing. A board that cannot be read fails
  the run naming the board. One item that cannot be rendered is counted as failed and keeps its
  stored copy.
- **Status page.** Components `Linear` and `Trello`: OFF "not connected — connect it on the
  Sources page" / OFF "connected, nothing chosen yet" / DOWN with the last failure / UP with
  document count and last sync.
- **Creating.** When a source is connected with something chosen, the model gets
  `list_linear_teams` / `create_linear_issue` or `list_trello_boards_and_lists` /
  `create_trello_card`. A create tool only stores a `pending_action` row. docs0 then posts a
  draft in the thread (kind, target, title, description excerpt) with **Create** and
  **Cancel** buttons. Only the person who asked can click; a draft older than an hour is
  expired; a double click creates exactly one item; success replaces the draft with a link,
  failure shows a plain apology and records the error on the row. Creation is the only write
  docs0 makes (`issueCreate`, `POST /cards`).

## C8. Grain and Slack history (Phase 4)

Grain notes and transcripts as shared knowledge (Phase 4b, built). Slack public channels as shared
knowledge; private channels as restricted knowledge visible only when every audience member
is in that channel. Introduces the audience filter (Phase 4a, built). **Public-channel rule:** restricted
knowledge is used only in DMs and in private channels (and group DMs) where every member is entitled; a public
channel, or a channel whose privacy cannot be determined, only ever gets shared knowledge (see `spec/agent.md`).

**Slack history (built).** Source `Slack history` (kind `SLACK`), using the bot token already in
settings. The admin ticks channels on the Sources page; nothing is indexed until one is chosen.
A public channel is joined if needed (`conversations.join`); a private channel must already have
had docs0 invited, otherwise the page says "invite @docs0 to #name" and the run counts it skipped.
Per channel, the last `docs0.slack-history.days` days (default 180) become one document per thread
(`thread:<channel>:<root_ts>`, root plus replies) and one per channel per UTC day for messages
outside threads (`day:<channel>:<yyyy-mm-dd>`), as lines like `[2026-11-02 10:15] Priya: text` with
mentions resolved to names, links as `text (url)` and entities decoded. Joins/leaves and other
non-conversation messages and docs0's own messages are skipped; file titles are included as text; the
current content is re-rendered each time, so deleted messages disappear. The first run of each UTC day
reads the whole window; the runs in between read from the last clean run minus a day and re-read
threads whose latest activity is newer than the stored document. Slack rate limits (HTTP 429) are
waited out as `Retry-After` says, a bounded number of times. Public-channel documents are shared;
private-channel documents are restricted to the channel's current members. A de-selected, archived or
inaccessible channel has its documents removed at the next sync. One failing channel or thread is
counted and never aborts the run. DMs and group DMs are never indexed.

**Grain (built).** Source `Grain` (kind `GRAIN`, shared knowledge, assumed by the owner). The admin
pastes a Grain access token (a workspace token sees every recording of the workspace, a personal token
only the user's own) on the Sources page. docs0 tests it once with `GET /token-info`, stores it only if
Grain accepts it, AES-256-GCM encrypted like Linear's key, and shows the account name the test returned.
The page has Check access (count of recordings in the window, capped at 2,000), Sync now, the last five
runs and Disconnect. Only recordings that started in the last `DOCS0_GRAIN_HISTORY_DAYS` days
(`docs0.grain.days`, default 365) are indexed; a recording that leaves the window or disappears from Grain
is marked deleted at the next sync. One document per recording (`recording:<id>`, title the recording
title or `Meeting <date>`, mime `application/vnd.grain.recording`, owner the first recorder, modified time
the recording's end time): labelled Date, Duration, Participants, Recorded by and Link, then the AI
summary, action items and highlights as Grain provides them, then the transcript as
`[hh:mm:ss] Speaker: text` with consecutive lines by one speaker merged. Details and transcript are fetched
only for recordings whose list-level time changed. A recording whose transcript is not ready is indexed
with what exists and looked at again at each sync for 7 days. Grain's rate limit (429 with `Retry-After`) is
waited out a bounded number of times. A rejected token fails the run with "Grain rejected the access token.
Reconnect Grain on the Sources page." and deletes nothing; one failing recording is counted and never aborts
the run. Status page component `Grain`. docs0 only reads from Grain.

## C9. Gmail (Phase 5)

**Built.** Source `Gmail` (kind `GMAIL`, visibility `SHARED` at source level; **every document is
restricted**). Each mailbox is private to its owner: mail is used only when every person who will see
the answer is a principal of the document, in practice the owner's own DM with docs0.

**Credential.** A Google *service account* key (JSON) pasted by the admin on the Sources page (domain-wide
delegation is the only way an admin can connect every mailbox; the Drive flow stays OAuth). Validated as a
service-account key (`type`, `client_email`, `private_key` readable, numeric `client_id`) and stored whole,
AES-256-GCM encrypted, in `source_credential.encrypted_secret`. It is never logged, echoed or rendered: the
page shows only the service account's email and client ID (with setup steps for the Workspace Admin console:
Domain-wide delegation, scope `gmail.readonly`, plus `admin.directory.user.readonly` only for the directory
import). docs0 is strictly read-only toward Gmail and the directory.

**Mailboxes.** `source.config` = `{"mailboxes":[{"email","viewers":[...]}], "adminEmail": "..."}`. The admin
types addresses (one per line, validated, must be on a domain) or gives an admin email and loads people from
the Admin SDK (`users.list`, non-suspended) and ticks them. Per mailbox an optional "also visible to" list
(shared inboxes). Nothing is indexed until at least one mailbox is chosen. **Check access** makes one
`users/{email}/profile` call per mailbox and shows a tick with the message count or the plain reason
(delegation not authorised, Gmail API not enabled, not found, suspended).

**Client.** `GmailClient` (network edge) and `HttpGmailClient` on plain REST (`java.net.http` + Gson, no Gmail
library). Access tokens for `sub=<mailbox>` come from google-auth's `ServiceAccountCredentials` behind a
`TokenSource`, cached per mailbox until two minutes before expiry. Calls: `threads.list` (`q`, `maxResults`,
`pageToken`), `threads.get?format=full`, `getProfile`, directory `users.list`. 429, 5xx and rate-limit 403s are
waited out as `Retry-After` says, a bounded number of times. Errors are mapped to plain messages; nothing
contains a key, token or URL.

**Connector.** One document per thread from the last `docs0.gmail.days` days (`DOCS0_GMAIL_HISTORY_DAYS`,
default 90) matching `after:<epoch> -in:spam -in:trash -in:draft -category:promotions -category:social
-category:forums`. `external_id` `gmail:<mailbox>:<threadId>`; title the subject (`(no subject)`); body the
messages in order, each with From/To/Cc/Date then its text (plain preferred, else HTML converted; quoted
`>` lines, "On ... wrote:" tails and short `-- ` signatures trimmed; attachment names listed, never fetched),
capped at about 100,000 characters; `url` `https://mail.google.com/mail/u/<mailbox>/#all/<threadId>`; owner the
mailbox; `modified_at` the last message; mime `application/vnd.google.gmail.thread`; principals the mailbox
plus its viewers, lowercase. A thread's Gmail `historyId` is stored in `document.version` (V6) and a thread is
re-fetched only when it changes. Principals are refreshed on every run (`refreshAccess`), so a removed viewer
loses access at the next sync even for unchanged threads; threads no longer listed, and all mail of a mailbox
that is no longer chosen, are marked deleted. A rejected delegation skips that mailbox (reason shown on the
page, counted failed); a failing thread or mailbox never aborts the run; a rejected or unreadable key fails
the run and deletes nothing. Status component `Gmail`.

**Matching to Slack.** Mailbox principals are emails, so the Slack app needs `users:read.email`; when a
person's email is unknown their mail is hidden from them. Mail is used in a DM with the owner or viewer and in
a private channel whose every member is a principal, never in a public channel. The key's `token_uri` must be
Google's (`https://oauth2.googleapis.com/token`, or absent): any other value is refused at paste time and the
token endpoint is forced to Google's whatever a stored key says; key fields over 500 characters are refused.

**Admin dashboard.** Answers that cited a restricted document are never rendered on admin pages (see
`spec/agent.md`, "Admin pages and private answers").

## C10. Proactive and insights (Phase 6)

In opted-in channels docs0 offers an answer when it recognises a repeat question. It
flags answers that rely on a stale document and lets an admin nudge the owner. Insights
(a dashboard page and an optional weekly digest) report top themes, knowledge gaps and the
stale documents people rely on; DM questions are counted without attribution.

**Repeat-question answers.**

- Opt-in per channel on `/admin/channels` (`channel_setting.proactive`, default off).
- Considered only for a top-level message in an opted-in channel: not a thread reply, a bot,
  a subtype, an @mention, a DM or group DM, nor a message that already has replies; and only when it reads
  like a question (contains `?` or starts with a question word, 4 to 600 characters, not just a
  link).
- Gates: monthly budget not used up, at most `DOCS0_PROACTIVE_MAX_PER_HOUR` offers per channel
  per hour, two minutes between offers, a bounded number of judged messages per channel per hour, a
  channel is quiet for a day after a 👎 on one of its unprompted answers, and one `proactive_offer` row per
  message (never twice).
- Candidates: full-text match (OR-style, ranked, minimum rank, top 3) against past questions of the tenant
  that were asked in a channel, answered (kind ANSWER) with MEDIUM or HIGH confidence, got no 👎, did not
  use a private source, and were not asked in the same thread. DM questions are never the corpus. A question
  whose unprompted answer got a 👎 is retired as a candidate.
- If there are candidates, one cheap model call (`submit_judgement`: `same`, `reason`) decides whether the new
  message is essentially the same question. Its cost goes to `usage_log`, which counts toward the cap.
- If yes, the normal agent runs on the new question with the channel's audience (the same access rules as an
  @mention). The answer is posted in a thread on the person's message under the lead line "This looks like
  something we've covered before. Here's what I found:", logged as an interaction (so reactions and
  follow-ups work) and linked from the offer. It is posted only when it is an ANSWER with sources and at least
  medium confidence; otherwise docs0 says nothing (no status line, no apology) and the cost goes to `usage_log`.
  Any failure at any step means silence.

**Stale documents.** After an answer that cites documents, each cited document whose `modified_at` is
older than `DOCS0_STALE_DAYS` (default 365) is recorded in `stale_flag` (once per answer and document; private
documents are not recorded). The renderer, not the model, adds under the sources: ":hourglass: Based on a
document last updated <month year>. It may be out of date." using the oldest stale cited document. On the
Insights page an admin can press **Ask the owner to review**: the owner's email is resolved to a Slack user
(`users.lookupByEmail`) and sent a short DM with the title, date and link, at most once per document in 14
days; if the owner cannot be resolved the page says so. Admin-initiated only.

**Insights** (`/admin/insights`, last 7, 30 or 90 days): totals, top themes (one batched model call over at
most 300 distinct questions of 200 characters each, cached six hours in `tenant_setting`, **Refresh** at most every ten
minutes; DM and private-source questions are sent without ids so they count but are never quoted or chosen
as examples), knowledge gaps (UNKNOWN, LOW confidence or 👎; grouped by normalised text with a count; DM
questions shown only as "N questions asked privately", private-source questions as "Question not shown (private
source)"), stale documents people rely on, and repeat-question stats.

**Weekly digest.** `@Scheduled` on `DOCS0_DIGEST_CRON` in `DOCS0_TIMEZONE`, posted to the channel stored in
`tenant_setting` (nothing until an admin picks one), once per week, skipped when the budget is used up;
**Post digest now** posts at once. It holds totals, themes with counts, the top 5 gaps (public-channel questions
only) and the top 5 stale documents; never DM text, asker ids, private-source content or private-channel questions.

## C11. Multi-tenant onboarding (Phase 7)

Slack OAuth install, HTTP events, per-tenant credentials stored encrypted, self-serve
source connection. Delivered in two parts.

**Phase 7a: foundations and isolation (delivered).** A second company can be served by the same deployment
without any cross-tenant leakage; the pilot keeps working unchanged (Socket Mode with the `.env` Slack tokens,
one admin password, one configured tenant).

- The tenant is explicit everywhere: from the signed-in `AdminPrincipal` on admin pages, from the event's
  workspace (`team_id`) for Slack, from a loop over active tenants for scheduled jobs. No code assumes a single
  global tenant.
- A Slack workspace registry (`slack_installation`, bot token encrypted at rest) and a Slack client per tenant;
  events from an unregistered workspace are ignored.
- Per-tenant monthly budget (`tenant.monthly_budget_usd`: NULL uses the global `DOCS0_MONTHLY_BUDGET_USD`, 0
  means no budget at all and refuses every question; a company that installs itself starts with
  `DOCS0_NEW_TENANT_BUDGET_USD`, default 5, the pilot tenant with the global default), and tenant suspension (questions refused politely, syncs and jobs skipped).
- Composite foreign keys so the database refuses cross-tenant references, a full tenant-predicate audit, and a
  two-tenant test suite that proves isolation for search, answers, corrections, feedback, conversations,
  proactive answers, stale flags, insights, digest, costs, budgets, sync, credentials, the Slack registry,
  Slack event handling and the admin pages.
- Not in 7a: there is still no way to *add* a tenant or its Slack workspace through the product (tenants and
  installations are created in code or SQL), and the only admin sign-in is the pilot's.

**Phase 7b: self-serve onboarding (delivered).** Enabled by setting `DOCS0_SLACK_CLIENT_ID`,
`DOCS0_SLACK_CLIENT_SECRET` and `DOCS0_SLACK_SIGNING_SECRET` (multi-tenant mode, which also wants a public https
`DOCS0_PUBLIC_URL`); with them blank the pilot behaves exactly as before.

- **Add to Slack.** `GET /slack/install` redirects to Slack's OAuth v2 page with the bot scopes (one list,
  `SlackScopes`, checked by a test against both manifests) and a single-use state bound to the browser session
  (10 minutes). `GET /slack/oauth/callback` exchanges the code behind the `SlackOAuth` interface, then creates a
  tenant for the workspace (slug from the team name, unique) or renews the installation of the tenant that already
  has it, keeping its data. The bot token is stored encrypted (`SlackWorkspaces.register`). The installer's email
  (users.info) becomes the first admin. A suspended tenant is never reactivated by a reinstall. The installer gets
  a welcome DM (failure tolerated). `app_uninstalled` and `tokens_revoked` (bot token) mark the installation
  revoked (`slack_installation.revoked_at`): nothing is served for it, the tenant and data stay.
- **Events over HTTP.** `POST /slack/events` and `/slack/interactions` verify `X-Slack-Signature` (HMAC-SHA256 of
  `v0:<timestamp>:<raw body>`, constant-time compare, 5-minute window; failure is a bare 401) on the raw bytes
  before parsing anything. Verified requests get 200 at once and are handled on virtual threads; the payload is
  parsed with the Slack SDK model classes and passed to the same `SlackGateway` methods the Socket Mode handlers
  call, which resolve the tenant by `team_id` (unknown or revoked: ignored). `url_verification` returns the
  challenge; Slack retries are dropped by event id and by the handlers' own de-duplication. Socket Mode runs only
  when `DOCS0_SLACK_APP_TOKEN` and the bot token are set (the pilot); both transports may coexist.
- **Sign in with Slack.** `SlackAdminAuthenticator` (consulted first in this mode): `GET /admin/login` ->
  Slack OpenID Connect with state and nonce in the session; the callback maps the person's workspace to the tenant
  with that `slack_team_id` and admits them if their email is in `tenant.admin_emails` or Slack says they are a
  workspace admin or owner. The `AdminPrincipal(tenantId, name, email, slackUserId)` lives in a server-side session
  (id rotated at sign-in, `HttpOnly`, `SameSite=Lax`, `Secure` on https, 8-hour idle timeout and a 12-hour absolute
  lifetime); `POST /admin/logout` ends it. The "may administer" check (admin list or workspace admin) is repeated
  at most every 5 minutes per session; a failed check ends the session, and when Slack cannot be reached the last
  good answer holds for 15 more minutes, then the session ends. The install and sign-in pages and their callbacks
  are limited to 30 requests a minute per address (the forwarded address counts only for connections from this
  machine), and only a request that starts a flow creates a session.
  Browsers without a session are redirected to `/admin/login`, other clients get 401, and the pilot's loopback
  shortcut is off (in the pilot it also requires a `localhost`, `127.0.0.1` or `[::1]` Host header; set
  `DOCS0_ADMIN_PASSWORD` otherwise). A suspended tenant's admins see only a suspended page. The session's tenant is the only tenant
  any admin page or form can touch.
- **Landing page and Start.** `/` is a public landing page with **Add to Slack** and **Sign in**, and `/health`
  returns only `{"status":"UP"}`. `GET /admin/start` is the company's checklist (Slack connected, each source's
  status with a link, inviting docs0, a sample question, the budget) and manages up to 20 admin emails.
- **Links and unfurling.** A link in a model-written answer stays a link only when it is https and leads to the
  host of a cited document or to `slack.com`; any other URL is shown as code text. Posted messages set
  `unfurl_links` and `unfurl_media` to false (`chat.update` has no such option).
- **Manifests.** `slack/manifest-distributed.yml` (HTTP, `socket_mode_enabled: false`) next to the pilot's
  `slack/manifest.yml`; a test requires the same bot scopes and events.

Intentionally not built: per-tenant Anthropic keys (the operator's key and model bill are shared), billing or
plans, organization-wide (Enterprise Grid) installs, a super-admin console (per-tenant budget and suspension are
set in SQL), and Google Drive for other companies still depends on the shared Google OAuth client being an
External, verified app.
