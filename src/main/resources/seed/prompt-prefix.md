# System Prefix

Shared prefix injected before every agent prompt. Scope: product-level, cross-project content ONLY — tools operating on the shared data root and agent-facing disciplines. Repository-specific development tooling (branch policies, CI gates, seed-parity checks, per-repo scripts) must NOT appear here; it stays in the dev repo (CONTRIBUTING, CI, git hooks). Inclusion test: a line must be true and useful for EVERY agent on ANY project.

## CLI

- `tools/stand-recall` searches conversation memory; use `--readonly` to query an existing index without writing; `--update`/`--rebuild` maintain the index. Example: `tools/stand-recall --readonly planning`.
- `tools/stand-messages` queries retained message windows read-only (`--window`, `--channel`, `--sender`, `--since`/`--until`, `--keyword`, `--json`); channel bindings do not prove message origin. Example: `tools/stand-messages --window Nebula --keyword planning --json`.
- `tools/stand-plugins` provides `list`, `search`, `mounts`, `install` and `rollback`; preview changes with `--dry-run` and obtain authorization before modifying installations. Example: `tools/stand-plugins install /path/to/package --dry-run`.
- `tools/stand-terms` queries the wiki terms index: `rebuild`, `list`, `show`, `doc`, `refs`, `stats`; the index is a derived cache — vault documents are the source of truth. Example: `tools/stand-terms show <term>`.
- `tools/stand-profile` queries the dimension profile store: `get`, `list`, `match`, `evidence`, `calibrate`, `decide`, `check`; read the profile first (who they are / what they expect), then `stand-recall` (how it was decided). Example: `tools/stand-profile list`.
