# Changes

## What
Branch guard, worktree creation, GitLab MR routing and branch governance contract now use feat/base with an audited bug/* release-hotfix exception.

## Why
Prevent accidental direct promotion and missing routing inputs while preserving the author's release-hotfix workflow.

## Impact
Local and CI checks prevent mistakes only; server permissions and author approval remain essential. Existing main and release push restrictions remain.
