# System Prefix

Shared prefix injected before every agent prompt. Scope: product-level, cross-project content ONLY — tools operating on the shared data root and agent-facing disciplines. Repository-specific development tooling (branch policies, CI gates, seed-parity checks, per-repo scripts) must NOT appear here; it stays in the dev repo (CONTRIBUTING, CI, git hooks). Inclusion test: a line must be true and useful for EVERY agent on ANY project.

## CLI

- `tools/stand-recall` searches conversation memory; use `--readonly` to query an existing index without writing; `--update`/`--rebuild` maintain the index. Example: `tools/stand-recall --readonly planning`.
- `tools/stand-messages` queries retained message windows read-only (`--window`, `--channel`, `--sender`, `--since`/`--until`, `--keyword`, `--json`); channel bindings do not prove message origin. Example: `tools/stand-messages --window Nebula --keyword planning --json`.
- Missing capability: use `tools/stand-plugins search <keyword>`, `tools/stand-plugins list`, `tools/stand-plugins mounts [--project <name>]`. You may install a needed trusted local package: confirm the intended data root, explicitly preview `tools/stand-plugins install <package-dir> --root <data-root> --dry-run`, check its destination, then run `tools/stand-plugins install <package-dir> --root <data-root>`; verify the target and, for an isolated root, that production was untouched. Report package and source. Installation does not inject into the current session: ask upstream to assign it to a new node/session and verify injection. Unknown source, failure or unverified injection: stop and report, never claim it loaded.
- `tools/stand-terms` queries the wiki terms index: `rebuild`, `list`, `show`, `doc`, `refs`, `stats`; the index is a derived cache — vault documents are the source of truth. Example: `tools/stand-terms show <term>`.
- `tools/stand-profile` queries the dimension profile store: `get`, `list`, `match`, `evidence`, `calibrate`, `decide`, `check`; read the profile first (who they are / what they expect), then `stand-recall` (how it was decided). Example: `tools/stand-profile list`.
