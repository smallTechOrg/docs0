# docs0 — product brief

docs0 is a multi-tenant knowledge agent for a company's docs and tools. The first tenant
is smallTech. The team talks to it in Slack; it answers from the company's own content
with links and quoted snippets, and it learns from the team's corrections.

## Decisions from intake

| Area | Decision |
|---|---|
| Sources | Google Drive, Linear, Trello, Grain, Slack history, Gmail. Drive first; the rest arrive one phase at a time. |
| Outputs | Cited answer in thread (links + quoted snippets), cross-tool summaries, FAQ and team insights. |
| Surfaces | @docs0 in channels, DMs, proactive answers in opted-in channels. No slash command. |
| Session | A thread is one conversation. Follow-ups in a thread need no @mention. Anyone can join. A DM is one long-running chat. |
| Memory | Every prompt and answer is logged. Only explicit corrections change future answers. |
| DM privacy | DM questions feed insights anonymised: counted, never attributed or quoted to others. |
| Uncertainty | Ask a clarifying question, or give a flagged best guess, or say "I don't know", and suggest who to ask. |
| Retrieval | No vector RAG, no embeddings. Agentic search: Claude drives search and read tools over a synced keyword index and reads whole documents. |
| Proactive | Suggested follow-ups, stale-doc flags, answers to repeat questions in opted-in channels. |
| Write access | Read-only everywhere, except creating new Linear issues and Trello cards. Never edit or delete. |
| Access model | Admin-selected Drive, Linear, Trello, Grain content is shared team knowledge. Private Slack channels and Gmail are restricted: an answer uses only what every member of the conversation's audience can access. |
| Gmail | Workspace admin connects all mailboxes (domain-wide delegation). A mailbox is visible only to its owner. |
| Corrections | 👎 flags an answer and docs0 asks what was wrong. A reply can correct it. Admin reviews corrections. A correction is cited by author when used. |
| Answer display | Answer, numbered source links, quoted snippets. Live status line while working. |
| Cost | Hidden from the team. Totals on the admin dashboard. Hard cap of $100 per month; docs0 stops answering when it is reached. Model: Claude Sonnet 5.5. |
| Audit | Admin sees corrections and answers people were unhappy with. |
| Scale | Under ~5k Drive files. Content fresh within the hour. |
| Stack | Java backend (binding). Anthropic is the only AI provider. Slack Socket Mode. Drive is connected by an admin signing in with Google on the dashboard (no service account). |

## Assumptions

- Assumed: Grain notes are shared team knowledge, like Drive.
- Drive is connected by an admin's Google sign-in and a folder selection (user decision,
  replacing the earlier service-account plan). Domain-wide delegation is set up in the
  Gmail phase.
- Assumed: "answer repeat questions" re-derives the answer from sources and approved
  corrections; it does not replay an old answer.
- Assumed: tenant isolation is built in from the first migration even though only one
  tenant exists, because retrofitting it is costly.
- Assumed: reliability bar is an internal pilot.

## Phase 1 core path

@docs0 in a Slack channel or DM answers a question from indexed Google Drive docs, with
source links and quoted snippets, in a thread that supports follow-ups.
