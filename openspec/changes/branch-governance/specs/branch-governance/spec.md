## ADDED Requirements

### Requirement: [BG-REQ-001] Branch governance guards

New development branches SHALL use feat/* or bug/* and derive from feat/base, except release hotfix bug/* branches may derive from main with target main. MR checks SHALL accept feat/*, bug/* and grandfathered fix/* to feat/base, main to feat/base, feat/base to main, and bug/* to main only with an auditable main baseline SHA that is an ancestor of both main and source HEAD. Hotfixes require author approval; repository checks cannot prove authenticity or grant authority. Missing MR input SHALL fail closed. Push checks SHALL reject main and release/* updates and deletions, and feat/base creation, deletion and non-fast-forward updates, unless explicitly overridden; malformed input SHALL fail closed. Only the author may create or delete feat/base. Server-side permissions, not local checks, enforce release authority.

#### Scenario: [BG-SCN-001] branch and MR checks

- **WHEN** branch and MR source names are checked
- **THEN** valid allowed names pass and invalid or unauthorized names fail.

#### Scenario: [BG-SCN-002] push destination checks

- **WHEN** Git pre-push ref records are checked
- **THEN** protected destinations fail without an explicit override, including deletion and multi-ref pushes, and malformed records fail even with an override.

### Requirement: [BG-REQ-002] Downstream release flow

The author SHALL derive every release branch — release/beta, release/stable, and any future release/* — from the promoted main only; no release branch SHALL be derived from another release branch. Release automation SHALL select the corresponding channel on those branches and SHALL NOT write changes back to main.

#### Scenario: [BG-SCN-003] Release branch routing

- **WHEN** a pipeline runs on release/beta or release/stable
- **THEN** the full GitLab pipeline and the corresponding release channel are selected, while legacy branch names do not select them.

#### Scenario: [BG-SCN-004] No upstream writeback

- **WHEN** stable release automation runs
- **THEN** it does not commit or push version changes to main.

### Requirement: [BG-REQ-003] Worktree lifecycle: one creation entry and reclamation on landing

A development worktree SHALL be created only through the unified entry `scripts/stand-wt.py new <wtname>`, which SHALL place it at `<project>/.stand/worktrees/<wtname>/`; a dispatch brief or a batch MUST NOT hand-write `git worktree add` with a self-chosen path. As soon as a branch has landed on an integration line — its tip is an ancestor of `feat/base` or `main` — its worktree SHALL be reclaimed as part of that landing action, by `git worktree remove <path>` without `--force`. Reclamation SHALL be skipped and the tree named in the landing report when any precondition fails: the tree's `git status --porcelain` is not empty, a process holds it (`lsof +D <path>` returns results), or it carries `MERGE_HEAD`, `rebase-*` or `CHERRY_PICK_HEAD`. The branch SHALL always be kept; `git branch -d` is a separate retirement decision and is not part of this rule. The landing report SHALL record which worktrees were removed, how much was reclaimed, and which were skipped with the reason.

**Verification**: `scripts/check-worktree-cleanup.mjs` judges every linked worktree from `git worktree list --porcelain` against `main` and `feat/base` and reports each merged-but-hanging tree as path, branch and merge criterion; `scripts/merge-sink.py` performs the reclamation under the three preconditions and keeps the branch.

#### Scenario: [BG-SCN-005] One creation entry

- **WHEN** a worktree is needed for a batch
- **THEN** it is created by `scripts/stand-wt.py new` and lives under `<project>/.stand/worktrees/<wtname>/`, and a hand-written `git worktree add <arbitrary path>` is not used.

#### Scenario: [BG-SCN-006] Reclamation on landing

- **WHEN** a branch has been merged into the integration line and its worktree is clean, unheld and free of an in-flight operation
- **THEN** the landing action removes that worktree with `git worktree remove` and keeps the branch, and when a precondition fails it skips the removal, names the tree and the reason in the landing report, and never forces the deletion.

#### Scenario: [BG-SCN-007] Merged branch still holding a worktree

- **WHEN** the worktree-cleanup gate runs in a checkout that has linked worktrees
- **THEN** every worktree whose HEAD is an ancestor of `main` or `feat/base` is reported as a violation with path, branch and merge criterion and the gate exits 1, while a checkout with no judgeable worktree — a CI or hosted primary checkout, or an unavailable integration ref — passes with an explicit statement that there was nothing to judge and is never reported as either a residual or a clean claim.
