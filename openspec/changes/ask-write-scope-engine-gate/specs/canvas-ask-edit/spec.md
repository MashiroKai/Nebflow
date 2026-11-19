## ADDED Requirements

### Requirement: [CAE-REQ-101] One normalising write-path verdict
A file-bound Canvas Ask session SHALL edit exactly one document: its bound file. Every write target SHALL first be normalised (relative spellings resolved against the bound document's directory, `.` and `..` consumed) and then compared by file identity (real path plus `Files.isSameFile`), so that every spelling equivalent to the bound document is accepted while every other target is refused. A refusal SHALL state the reason, SHALL state that the session edits only its bound document, SHALL point at the main-session handoff as the sanctioned route, and SHALL leave the targeted file untouched. This requirement revises the target-gate wording of CAE-REQ-001: the criterion is document identity, not a spelling blacklist.

#### Scenario: [CAE-SCN-101] Equivalent spelling of the bound document is allowed
- **WHEN** a file-bound Ask proposes an edit whose spelling normalises to the bound document (exact path, `.`, a `..` round trip, a relative form, `./`, a symbolic or hard link resolving to it, an alias mount, a case variant)
- **THEN** the engine SHALL accept the proposal and the write SHALL land in the bound document only.

#### Scenario: [CAE-SCN-102] Any other target is refused readably
- **WHEN** a proposal resolves to anything else (sibling file, relative sibling, `..` reaching another file, absolute path outside the workspace, symlink to another file or outside the sandbox, dangling link, directory, empty string, system path)
- **THEN** the engine SHALL refuse before writing, SHALL name the reason, SHALL state the scope rule and the handoff route, and SHALL leave the targeted file unchanged.

### Requirement: [CAE-REQ-102] Every verdict is audited
Each write-path verdict (allow and deny) SHALL append a machine-readable row — time, identity, tool, requested spelling, normalised path, result, reason — to an audit log under the data root, and SHALL report the same content on the ask log channel. A failing audit sink SHALL NOT convert a refusal into a write.

#### Scenario: [CAE-SCN-103] Allows and denials both leave a trace
- **WHEN** an in-scope edit is accepted and an out-of-scope edit is refused
- **THEN** the audit log SHALL contain one row per verdict carrying the requested spelling, the normalised path and the result.

#### Scenario: [CAE-SCN-104] The shell is not a second write channel
- **WHEN** the file-bound Ask attempts to write through the shell
- **THEN** the attempt SHALL be refused and audited, and no file SHALL be created.

### Requirement: [CAE-REQ-103] Complex tasks are handed to the main session
A file-bound Ask SHALL hand work beyond its one document to the main session as a structured request (task, paths, reason, expected result) over the existing main-session message channel — never by widening its own write face — and the request SHALL reach the main session as human-readable text while remaining machine-readable in the injection metadata. The Ask panel SHALL show a visible handed-off state with a summary.

#### Scenario: [CAE-SCN-105] A structured handoff reaches the main session
- **WHEN** a file-bound Ask requests a task spanning several documents, several steps, or a command/build
- **THEN** the main session SHALL receive the structured request and the Ask panel SHALL show the handed-off state with its summary.

#### Scenario: [CAE-SCN-106] An impossible handoff refuses explicitly
- **WHEN** a handoff is requested with no bound document or with the main-session channel unavailable
- **THEN** the engine SHALL refuse explicitly and the panel SHALL NOT show a handed-off state.

### Requirement: [CAE-REQ-104] The ask tool face is closed and its inventory is derived
The ask authorization face SHALL contain no direct write tool, the ask shell leg SHALL be replaced by the query-only validator before any local or remote routing, and the ask prompt's tool inventory SHALL be derived from the tool registry instead of hand-copied. Per-identity tool-face readings SHALL come from one enumeration point.

#### Scenario: [CAE-SCN-107] Per-identity readings show the face is closed
- **WHEN** the tool face is read for every identity
- **THEN** the file-bound Ask identity SHALL expose the ask edit/write tools and the handoff tool but no direct write tool, and every other identity SHALL expose no ask tool.

#### Scenario: [CAE-SCN-108] The prompt cannot drift from the registry
- **WHEN** the ask prompt is assembled
- **THEN** its tool section SHALL be generated from the registry and SHALL state both hard constraints (edits only the bound document; hands complex tasks to the main session).
