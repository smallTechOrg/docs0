# Roadmap

Every phase ends in something the team can try in Slack. Gate commands run from the repo
root.

## Phase 0 — Scaffold

- **Goal.** A Spring Boot app that boots with no credentials, connects to Postgres, runs
  migrations and serves `/health`.
- **Gate.** `./mvnw -q verify` then `./mvnw spring-boot:run` and
  `curl -sf localhost:$DOCS0_PORT/health` returns `"status":"UP"`.

## Phase 1 — Drive answers in Slack

- **Goal.** @docs0 answers from indexed Google Drive docs with links and quoted snippets,
  in a thread with follow-ups.
- **Slices (disjoint paths).**
  1. *Knowledge + Drive sync* — `knowledge/`, `source/`, `source/drive/`, migration for
     `source`, `document`, `sync_run`. Capability C2.
  2. *Agent* — `agent/`. Capability C3 and `agent.md`. Depends only on the
     `DocumentRepository` interface.
  3. *Slack + conversation log* — `slack/`, `conversation/`, migration for
     `conversation`, `interaction`, `slack/manifest.yml`. Capability C1.
  4. *Status page* — `web/`. Capability C4.
  5. *Connect Google Drive* — `web/admin/`, `crypto/`, changes in `source/drive/`,
     migration V2. Replaces the service account. Capability C2.
- **Gate.** `./mvnw -q verify` (unit and Postgres integration tests for every slice),
  plus a live check with real keys: one sync run against the real Drive prints a
  non-zero document count, and one real question through `AgentService` returns an
  answer with at least one verified citation.
- **User test.** On the Sources page: connect Google Drive, pick a folder, sync now.
  In Slack: @docs0 with a question whose answer is in a Drive doc; check
  the link and quote; ask a follow-up in the thread without @; DM it; ask something
  that is not in any doc.
- **Deferred.** Everything in Phases 2–7. The status page is read-only and unprotected
  on localhost.

## Phase 2 — Feedback, corrections, admin dashboard

- **Goal.** 👎/👍, reply-to-correct, admin review queue, corrections applied and cited,
  cost totals. Capabilities C5, C6.
- **Gate.** `./mvnw -q verify`; live: a correction approved on the dashboard changes the
  answer to the same question and is cited.
- **User test.** 👎 an answer, correct it in thread, approve it on the dashboard, ask
  again.

## Phase 3 — Linear and Trello

- **Goal.** Answers draw on Linear and Trello; docs0 can create an issue or card on
  request. Capability C7.
- **Gate.** `./mvnw -q verify`; live sync of both tools prints non-zero counts.
- **User test.** Ask the status of a known issue; ask docs0 to file one.

## Phase 4 — Grain and Slack history, audience-aware access

- **Goal.** Grain notes and Slack history as knowledge; restricted sources honoured by
  conversation audience. Capability C8.
- **Gate.** `./mvnw -q verify` including audience-filter tests; live sync counts.
- **User test.** Ask in a public channel about something said only in a private channel
  (must not leak); ask the same in that private channel (must answer).

## Phase 5 — Gmail

- **Goal.** Per-owner mail knowledge via domain-wide delegation. Capability C9.
- **Gate.** `./mvnw -q verify`; live sync of one mailbox.
- **User test.** DM docs0 about an email only you received; a colleague asks the same
  and gets nothing from your mail.

## Phase 6 — Proactive and insights

- **Goal.** Repeat-question answers in opted-in channels, stale-doc flags, weekly
  insights. Capability C10.
- **Gate.** `./mvnw -q verify`; live: a repeated question in an opted-in channel gets an
  unprompted answer.

## Phase 7 — Multi-tenant onboarding

- **Goal.** A second company can install and connect its own tools. Capability C11.
- **Gate.** `./mvnw -q verify` including cross-tenant isolation tests.
