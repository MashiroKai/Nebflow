## ADDED Requirements

### Requirement: [CAE-REQ-001] File-bound edit proposals
A Canvas Ask session SHALL propose edits only to its existing bound absolute text file. Edit and whole-file rewrite SHALL use a snapshot as the diff baseline, reject traversal and symbolic links, and SHALL NOT create, delete, or move files. Each proposal SHALL remain pending in memory until independently accepted or rejected. Accept SHALL write the source file only if its snapshot remains current and reload the Canvas content; reject SHALL leave it unchanged. The diff card SHALL identify the path and show added and removed lines.

#### Scenario: [CAE-SCN-001] Proposal, acceptance and rejection
- **WHEN** a file-bound Ask proposes an in-scope text edit
- **THEN** the file remains unchanged until its card is accepted, and rejection leaves it unchanged.

#### Scenario: [CAE-SCN-002] Out of scope or stale
- **WHEN** a proposal targets another path, a symlink, a binary file, or an outdated snapshot
- **THEN** the engine SHALL refuse with an explicit error and SHALL NOT modify the file.

### Requirement: [CAE-REQ-002] File-bound Ask shell queries
For file-bound Canvas Ask, Bash SHALL default to refusal and accept only validated read-only command pipelines; shell expansion, command substitution, redirection, compound operators and unsafe arguments SHALL be refused. Git SHALL be restricted to read-only subcommands with pager disabled. Other Ask contexts SHALL retain their existing behavior. The allowlist and argument validation are best-effort enforcement; parser defects remain a residual risk. This requirement revises the file-bound Ask Bash behavior in DSA-SCN-007 and DSA-SCN-008.

#### Scenario: [CAE-SCN-003] Refuse write primitives
- **WHEN** file-bound Ask attempts a write primitive, unsafe argument, unknown command, or unparseable input
- **THEN** the engine SHALL return an explicit refusal before local or remote execution.
