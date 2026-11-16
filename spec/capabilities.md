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
- **Status line.** docs0 immediately posts a status message ("Searching Drive…"), updates
  it in place as tools run ("Reading *Pricing 2026*…"), then replaces it with the answer.
  Updates are throttled to one per second.
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

`/` shows: a link to the Sources page; Slack, Anthropic, Drive, Linear and Trello connection state with the reason when one is off;
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

Grain notes and transcripts as shared knowledge. Slack public channels as shared
knowledge; private channels as restricted knowledge visible only when every audience
member is in that channel. Introduces the audience filter.

## C9. Gmail (Phase 5)

Domain-wide delegation indexes each mailbox. Mail is visible only to its owner, so it is
used only in that person's DM with docs0.

## C10. Proactive and insights (Phase 6)

In opted-in channels docs0 offers an answer when it recognises a repeat question. It
flags answers that rely on a stale or contradicted document and notes the owner. A
scheduled digest reports top questions, unanswered questions and gaps; DM questions are
counted without attribution.

## C11. Multi-tenant onboarding (Phase 7)

Slack OAuth install, HTTP events, per-tenant credentials stored encrypted, self-serve
source connection.
