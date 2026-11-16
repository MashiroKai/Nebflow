# Plugin self-service CLI design

## Scope and validation

The repository CLI works without a project context. Discovery (`list`, `search`, `mounts`) remains read-only and does not grant a capability to an agent. Installation accepts a local directory, not an archive. The lightweight validator requires a JSON object in `plugin.json`, the canonical schema, a lowercase safe identifier as name, and nonempty version and description. At least one skill directory with `SKILL.md` or a nonempty `mcpServers` object is required; every skill directory must contain `SKILL.md` with name and description in frontmatter. A present MCP manifest must contain a nonempty `mcpServers` object. Symlinks and special files are rejected. Errors are collected and reported before writing; staged copies are validated again. This is structural screening, not complete YAML/schema validation, a security audit, or proof that an MCP server runs. The installer must review package content.

## Replacement and backups

A package is copied to a staging directory under the selected root. For a new name, rename places it in `plugins/<name>`; for an existing destination, the current directory is first copied to `plugins-backups/.backup-<name>-<timestamp>`, with a UTC numeric timestamp including microseconds. Replacement uses a single atomic directory exchange (`renamex_np` on macOS, `renameat2` on Linux); unsupported exchanges fail rather than using a non-atomic two-rename fallback. The old exchanged directory is removed with the stage after success. A root-local advisory lock serializes this CLI's writers, not unrelated external writers. Backup copying and exchange are separate operations; this is not a transaction across crashes or competing external writers.

## Rollback and isolation

`rollback <name>` chooses the lexicographically latest matching numeric timestamp backup, validates it and installs its copy. If a destination exists, rollback first creates another backup of that displaced version; it does not consume the selected backup. Consequently a subsequent rollback may select the version displaced by the previous rollback. Missing or invalid backups are errors.

`--root` selects the data root containing `plugins`, `plugins-backups`, the staging directory and lock file; absent an explicit root, normal environment/default root resolution applies. It is intended for fixture sandboxes, not a security boundary against malicious filesystem links or unrelated writers. `--dry-run` validates and reports the planned operation without creating the root, stage, backup or lock. Fixture tests must never target the live plugin root.

## Activation boundary

The engine registry compares the plugin directory tree signature when it is next read and rescans on change. The CLI does not call an external forced-reload endpoint or verify engine activation; disk replacement alone does not demonstrate that a running engine has consumed the new version. Existing running agents retain previously injected content, and installation does not alter dispatch permissions or grant an agent a mount. No restart is required or authorized by this design.
