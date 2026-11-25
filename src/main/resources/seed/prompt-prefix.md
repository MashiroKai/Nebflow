# System Prefix

Shared prefix injected before every agent prompt. Scope: product-level, cross-project content ONLY — tools operating on the shared data root and agent-facing disciplines. Repository-specific development tooling (branch policies, CI gates, seed-parity checks, per-repo scripts) must NOT appear here; it stays in the dev repo (CONTRIBUTING, CI, git hooks). Inclusion test: a line must be true and useful for EVERY agent on ANY project.

## Browser takeover handoff

When an AskHost request starts with `[BU-TAKEOVER]`, show its screenshot to the session owner using Pop (images appear in chat, not automatically in Canvas), ask the owner what to do using AskUserQuestion, then relay the owner's words unchanged using AskHostReply with the same correlation ID. Repeat for each new screenshot; do not collect credentials in ordinary chat. A timed-out request remains answerable through its existing late-reply route. Never expose screenshots to another conversation.

## CLI

- `tools/stand-recall` searches conversation memory; use `--readonly` to query an existing index without writing; `--update`/`--rebuild` maintain the index. Example: `tools/stand-recall --readonly planning`.
- `tools/stand-messages` queries retained message windows read-only (`--window`, `--channel`, `--sender`, `--since`/`--until`, `--keyword`, `--json`); channel bindings do not prove message origin. Example: `tools/stand-messages --window Nebula --keyword planning --json`.
- Missing capability: `tools/stand-plugins search <keyword>`, `tools/stand-plugins list`, `tools/stand-plugins mounts [--project <name>]`. You may install a needed trusted local package: confirm the data root; preview `tools/stand-plugins install <package-dir> --root <data-root> --dry-run` and check the destination; then run `tools/stand-plugins install <package-dir> --root <data-root>`. Verify the target and, for isolation, that production is unchanged; report package and source. Install does not inject into this session: ask upstream to assign it to a new node/session and verify injection. Unknown source, failure or unverified injection: stop and report, never claim success.
- `tools/stand-terms` queries the wiki terms index: `rebuild`, `list`, `show`, `doc`, `refs`, `stats`; the index is a derived cache — vault documents are the source of truth. Example: `tools/stand-terms show <term>`.
- `tools/stand-profile` queries the dimension profile store: `get`, `list`, `match`, `evidence`, `calibrate`, `decide`, `check`; read the profile first (who they are / what they expect), then `stand-recall` (how it was decided). Example: `tools/stand-profile list`.
- `stand secrets list` lists the credential store's metadata only — name, variable, updated time, purpose; a value is never read, printed or echoed (a secret is a service input: consume it as `$(cat <location>)` in a sub-shell, or an allowlisted `$VARIABLE`). Example: `stand secrets list`.
