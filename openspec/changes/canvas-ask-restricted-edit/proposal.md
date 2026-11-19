## Why

Canvas Ask needs reviewable, file-scoped edits while preserving query capability. Ordinary Bash is not read-only and can bypass an edit-only boundary, so the file-bound shell face must change together with the edit feature.

## Scope

Add in-memory, individually reviewable text-edit proposals and a fail-closed query-only Bash face for file-bound Canvas Ask. Retain other Ask contexts. The allowlist and argument validation are best-effort enforcement with parser defects as residual risk; do not represent them as an operating-system sandbox. No deployment or live-user-data changes are authorized by this change.
