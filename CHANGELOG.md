# Changelog

## Unreleased — beta candidate (source tip not finalized)

Candidate baseline `beed5aa486bed847f086a98258903dc42f7d6c60`, compared with `v2026.10.24-beta.1`. This is a review candidate, not a release approval or a statement about the eventual release tip. Assign the version from the release-day date only after the final source tip and duplicate-tag check are known.

### Added and changed

- Todo and scheduled-task entries now support separate name and detail text in the web UI; the backend continues to carry the detail field. This describes the name/detail split only, not completion of pending Task-query or passive-reminder work.
- The CLI and configuration use the `stand` identity in selected entry points. Existing `~/.nebflow` data-root and `nebflow.json` configuration fallback paths remain available; migration/rename behavior is not automatic in every path. See the compatibility notes in the evidence matrix before changing existing installations.
- Release workflow evidence in this baseline specifies the assembled jar as the beta artifact; desktop installer jobs are disabled. No installer/platform availability beyond that workflow was assessed.

### Fixed

- Attachment previews have an explicit supported-name/local-path contract and dedicated attachment preview contract tests; this does not claim that every attachment transport or remote path is covered.
- Search scope and chat-search routes have route-level tests. This does not claim every search provider or UI flow is regression-free.

### Compatibility and release gates

- This summary does not claim “no breaking changes.” Renamed CLI entry points, config/data-root naming, on-disk stores, and API/tool contracts have migration or compatibility surfaces that require release-owner review. The evidence matrix records concrete known transitions and unverified boundaries; retain a release hold if any applicable user migration action is unresolved.
- Unmerged T1/T2/T3 work, lifecycle production fixes, and Task query/passive-reminder changes are not represented as completed. Recheck the final integrated tip before release; do not infer their status from this baseline.
- No full build, full suite, end-to-end release rehearsal, or full compatibility audit was run for this changelog review. The old remote CI run is not evidence for this tip. Desktop installers remain disabled; the release workflow’s jar-only contract is the scoped statement.
