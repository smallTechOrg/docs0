# Build journal

Harness and runtime friction only. Not an app changelog.

Run started: 2026-11-17 01:24 IST.

- **No `support/` tree and no project-builder agent.** The skill refers to
  `support/agents/*.md`, `support/patterns/*.md` and a project-builder sub-agent; the
  installed skill folder contains only `SKILL.md`. → Ran the spec-writer and scaffold
  roles inline from the skill text. Lesson: the synced skill should ship its support
  tree, or the skill should say up front that inline is the only mode.
- **Intake contradictions needed follow-ups.** Multi-select answers produced two
  contradictions ("Read-only" with "Create Linear/Trello items"; memory answer versus
  the original idea). → Asked one targeted follow-up each. Lesson: mutually exclusive
  options should be single-select questions.
- **Embeddings gap surfaced late.** Choosing Anthropic plus "pre-built index" implied an
  embedding provider that Anthropic does not offer; the user then chose no vector RAG at
  all. Lesson: the technical round should ask the retrieval approach whenever the idea
  is knowledge search.
- **Safety check blocked `cd dir && rm -rf *` in the scratchpad.** → Used a fresh
  timestamped directory instead of cleaning. Lesson: never clean with a relative glob;
  always create a new directory.
- **User pasted the API key in chat mid-build.** → Wrote it to the git-ignored `.env`,
  validated with one call printing only the HTTP status. Lesson: intake should tell the
  user up front to put keys in `.env` rather than in chat.
