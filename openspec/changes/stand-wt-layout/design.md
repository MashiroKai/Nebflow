# Design

The primary checkout owns `.stand/worktrees` and `.stand/tmp`. The CLI discovers it from Git's worktree registry, inventories delivery and evidence separately, and matches delivery paths and SHA-256 content against the corresponding worktree HEAD. It records the last commit changing the matched path. Evidence cannot become committed. Cleanup recalculates status and checks age before deletion; force is an explicit exception. `check-delivery.py --tmp` additionally validates a local manifest while the committed-tree PR check remains usable on CI hosts without temporary artifacts.

The manifest is a regenerated view, not a source of truth. Git and file content are authoritative. Concurrent writers must be quiescent during audit or cleanup.
