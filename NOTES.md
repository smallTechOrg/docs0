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
- **Worktree-isolated workers started from the repo's initial commit, not the build
  branch.** All three reported their worktree was at the first commit with no spec or
  scaffold. → Each fast-forwarded to the pushed scaffold commit named in their brief's
  context and carried on; merges back were clean. Lesson: put the exact base commit in
  every worker brief and tell it to fast-forward first.
- **Slices could not load the full Spring context alone.** Each slice consumed a bean
  another slice was writing. → Fixed contracts in the scaffold, a context-free
  `TestDatabase` helper, a per-worker test database, and gates scoped to the slice's
  packages; the root ran the full suite after each merge. Lesson: decide the test
  isolation story before fanning out.
- **`-q` hides Maven's test summary.** Exit code alone is thin evidence. → Counted tests
  from `target/surefire-reports/*.xml` after every gate. Lesson: the gate command should
  print counts.
- **Connecting Drive was the slowest part of the gate.** A service account sees only
  what is shared with it; the connected Drive account could not share folders it did not
  own, and the gcloud login had no Drive scope. → Asked the owner to share, and started
  a gcloud re-login with Drive access. Lesson: intake should ask who owns the content
  and plan the access grant as a first-class setup step, ideally in the product UI.
