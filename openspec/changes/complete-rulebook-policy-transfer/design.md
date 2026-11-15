## Decisions

Keep reusable rules in the independent Stand dev repository. Add only two narrowly routed references where no coherent existing owner exists: workspace artifacts and delegated execution/recovery. Extend the existing prompt, verification, merge, release and provider guidance instead of duplicating it in a large replacement manual.

AGENTS.md remains human-owned. The user's subsequent explicit file-specific request authorizes simplifying it for this change; record a pre-edit snapshot and retain concise boundaries and routing. Historical statements are not copied into prompts. Rules whose authority still depends on a live specification are linked with a clear requirement to read and verify that specification, not inferred from old keywords or silently reconstructed.

Preserve task-local authorization distinctions: an implementation request does not itself authorize a Git commit, merge, push, publication or installed-runtime change. Explicit standing authorization remains usable within its stated scope. The existing narrow document-only direct-main exception remains a path-classification exception, not automatic authorization and not an exception for agent edits to AGENTS.md.

Keep source authoring and runtime deployment separate. Source prompt faces must match using byte comparison; an authorized later deployment reconciles the live face with backup and byte verification. Pending runtime synchronization is reported rather than concealed.

Retain proportional SDD and risk-based roles already approved by the user. Preserve substantive safety and acceptance constraints independently from reducing ceremony. No new runtime source, formatter, scheduler or parser is required for this transfer.

## Verification

Extend the plugin's existing static rulebook tests with positive contract checks and negative fixtures for weakened permission, background admission and missing routing. Check both current and proposed rulebook specifications with OpenSpec, and validate trace links. Keep all live mutation, commit, merge and deployment work out of scope.
