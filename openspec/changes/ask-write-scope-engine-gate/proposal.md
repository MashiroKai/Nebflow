# ask-write-scope-engine-gate

## Why

A file-bound Canvas Ask session must be able to edit exactly one document. The
previous target gate compared the requested path to the bound file as raw
strings, which confused two different questions: *which spellings of the same
document are legitimate* (relative forms, `.`, `..`, symlinks, case variants,
alias mounts) and *which targets are out of scope*. It refused equivalent
spellings of the bound document while giving no readable reason and no route out
for anything genuinely bigger than one file, and it left no audit trail of
refused attempts. The boundary also has to sit at the engine's authorization
point — a prompt instruction is not a boundary.

## What Changes

- One normalising write-path verdict: the requested spelling is resolved
  (relative against the bound document's directory), `..` / `.` are consumed,
  symlinks are followed, and identity is compared (real path + `Files.isSameFile`).
  Same document ⇒ allowed; anything else ⇒ refused.
- Refusals are explicit and readable, and carry the sanctioned way out: hand the
  task to the main session. Nothing fails silently and nothing writes silently.
- Every verdict (allow and deny) is appended to an audit log with identity,
  tool, requested spelling, normalised path, time and result.
- Complex tasks (several files, several steps, commands/builds) are handed to the
  main session as a **structured** request (task / paths / reason / expected)
  over the existing main-session message channel, and surface in the Ask panel as
  a visible "handed off" state.
- The ask tool face is proven closed per identity (no direct Write/Edit, Bash
  routed to the query-only executor before any routing) and the ask prompt's tool
  list is derived from the tool registry instead of being hand-copied.

## Impact

Affected specs: canvas-ask-edit (the file-bound edit boundary), canvas-ask-edit
presentation. Affected code: `nebflow.core.ask` (path boundary, audit, prompt),
`nebflow.core.tools` (ask edit tools, handoff tool), `nebflow.gateway` (ask WS
domain, handoff face), the Ask dock UI, and one new checkout-only gate.
No deployment, remote push or live-user-data change is authorized by this change.
