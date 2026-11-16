# Plugin self-service CLI (proposed)

## Intent
Agents can discover installed capabilities without project context and install a reviewed local package or restore the latest backup using the repository CLI. Installation is not a grant to an already-running agent.

## Scenarios and boundaries
- `list`, `search`, and `mounts` remain read-only and work outside a project.
- `install` validates a local directory's manifest and skill frontmatter or MCP capability, reports all detected structural errors, and refuses unsafe names and links. Replacement retains a timestamped backup and uses an atomic directory exchange. `rollback` restores the latest backup and backs up the displaced version.
- `--root` isolates all writes to a chosen data root; `--dry-run` performs no writes. Tests use disposable roots, never the live plugin directory.
- The engine detects directory changes on its next registry read. This CLI does not force an engine scan, modify dispatch grants, or change existing agent injections. No engine restart or Scala change is part of this proposal.

## Acceptance
Run `python3 tests/stand_plugins_install_test.py` for install, validation, dry-run and rollback, and `python3 tests/stand_plugins_cli_test.py` for query regressions. Sample the installed package with `install ... --dry-run`; do not write the live root. Review the CLI help before system-prefix adoption.
