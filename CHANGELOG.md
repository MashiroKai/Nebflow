# Changelog

## Unreleased — beta candidate (source tip not finalized)

Candidate notes for the integrated local baseline `beed5aa486bed847f086a98258903dc42f7d6c60`, compared with the previously published beta tag `v2026.10.24-beta.1`. This is a user-facing summary of the source-range review, not a claim that this commit is the final release tip. The candidate version is intentionally not assigned here; set it from the actual release-day date after the final source tip and duplicate-tag check are known.

### Added and improved

- Expanded the web console’s conversation, canvas, search, Ask, settings, and provider workflows, including clearer message/attachment presentation and better mobile task surfaces.
- Added more controllable tool execution and background-task handling, including bounded command waits and clearer task completion/evidence reporting.
- Improved project/delegate lifecycle observability and recovery reporting, with more explicit diagnostics for completion, failure, and stuck work.
- Continued the transition to the `stand` product identity while preserving legacy configuration and command compatibility where the implementation provides it.

### Fixed

- Addressed multiple web-console interaction and layout regressions across mobile, canvas, message rendering, search, and settings surfaces.
- Tightened attachment, upload, ownership, and path-handling behavior to avoid misleading success states and unsafe fallback behavior.
- Improved delegate/merger lifecycle accounting, completion forwarding, and failure visibility.
- Strengthened static and behavioral regression coverage around frontend contracts and backend/tool boundaries.

### Breaking changes

- No broad end-user breaking change is declared by this candidate summary. The repository contains compatibility transitions and command/configuration changes; consult the final release diff and migration behavior before publishing rather than treating this note as a guarantee of zero compatibility impact.

### Known limitations and verification scope

- This summary groups user-visible work across 1,416 non-merge commits in the range; it is not an item-by-item audit. Internal refactors, test-only work, and non-user-facing changes are intentionally omitted.
- Unmerged work and work outside the selected integrated baseline are excluded. In particular, this note does not assert completion of the separately tracked T1/T2/T3 work or any recent work that has not been verified and integrated at the final source tip.
- The baseline comparison does not establish that every recent change has passed the final release gates. The October 29, 2026 CI run examined for attribution was on a different remote `main` SHA and is not a validation of this candidate.
- Desktop installers remain disabled in the beta workflow; the shipping artifact path is the assembled jar. See `.github/workflows/release.yml`.
