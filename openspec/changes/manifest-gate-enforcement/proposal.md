# Manifest completion gate

Delivery from a linked worktree requires a valid temporary MANIFEST before completion. Primary checkouts and non-Git scratch directories are skipped; unknown or inaccessible checkout state is rejected.

Remote checks execute on the target device through the device channel. The target must provide Git, Python and `scripts/check-delivery.py`. For a remote node or delegate with a worktree whose target path is not reliably known, the gate refuses delivery rather than guessing a host path; the refusal reports `manifest-missing: remote worktree path unavailable` and asks for MANIFEST verification. An unavailable device or checker also rejects.

The E01 integration fixture previously lived under the repository worktree, so Git correctly identified it as part of that linked worktree and the new delivery gate rejected it without a manifest. Its workspace was moved to an actual non-Git temporary directory to test the intended scratch scenario; this is a fixture correction, not evidence that paths nested in a worktrees are non-Git.

Protected terminal rejection must be persisted as a failed authority envelope and projected as failed; recovery must preserve notification identity and explicitly report any missing manifest on replay of a legacy completed envelope.
