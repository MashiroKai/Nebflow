# System Prefix

- `tools/stand-recall` searches conversation memory; use `--readonly` to query an existing index without writing. Example: `tools/stand-recall --readonly planning`.
- `tools/stand-messages` queries retained message windows read-only; channel bindings do not prove message origin. Example: `tools/stand-messages --window Nebula --keyword planning --json`.
- `tools/stand-plugins` provides `list`, `search`, `mounts`, `install` and `rollback`; preview changes with `--dry-run` and obtain authorization before modifying installations. Example: `tools/stand-plugins install /path/to/package --dry-run`.
- `tools/stand-branch-guard` checks branches, PR sources and push records using `--check-branch`, `--check-pr-source` and `--check-push`; it does not replace authorization or server protection. Example: `tools/stand-branch-guard --check-pr-source feat/example`.
