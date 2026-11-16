## ADDED Requirements

### Requirement: Managed worktrees and artifacts
The system SHALL place project worktrees in `<project>/.stand/worktrees/<wtname>/` and their temporary artifacts in `<project>/.stand/tmp/<wtname>/delivery/<batch>/` and `evidence/`. Both `.stand` directories SHALL be ignored. Evidence SHALL never be committed; selected delivery SHALL be committed to `delivery/<batch>/`.

#### Scenario: Create a worktree
- **WHEN** a user creates a named worktree
- **THEN** the system creates the worktree and its temporary directories and manifest.

### Requirement: Audited manifest and retention
The system SHALL regenerate `MANIFEST.md` with one row per artifact including relative path, bytes, mtime and either `未入库` or `入库 <YYYY-MM-DD HH:mm> <commit-sha>`, based on SHA-256 comparison with committed delivery. The default TTL SHALL be seven days. Cleanup SHALL default to preview and SHALL retain uncommitted files unless forced.

#### Scenario: Expired evidence
- **WHEN** cleanup finds expired temporary evidence
- **THEN** it warns and retains the directory unless force is explicitly supplied.

#### Scenario: Review temporary delivery
- **WHEN** the delivery checker is supplied a temporary worktree artifact directory
- **THEN** it validates the manifest and committed status against the reviewed Git tree.
