# Tasks

- [x] Define branch and release policy in contributor and agent instructions.
- [x] Implement and test the branch, MR source, and push guards.
- [x] Wire the MR source check into the existing GitLab CI carrier.
- [x] Document the author-operated server protection checklist.
- [x] Route release automation to the downstream release lines, remove main writeback, and verify YAML and routing.
- [x] Re-run the full SDD gate after the unrelated plugin-selfserve-cli change gains its missing design document.
- [x] Write the worktree lifecycle pair (one creation entry, reclamation on landing) into CONTRIBUTING both languages, BG-REQ-003 and the design, align `scripts/merge-sink.py` to it, and add the `scripts/check-worktree-cleanup.mjs` guard with its negative control.
