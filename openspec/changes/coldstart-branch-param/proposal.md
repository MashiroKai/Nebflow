# Isolated branch cold-start driver

## Intent
Run the current, versioned cold-start driver against a specified commit without executing the target revision's driver. A detached temporary worktree is removed on exit. An isolated port and data root are mandatory. Dry-run prepares the checkout and builds without starting an instance.

## Acceptance
- Invalid revisions, protected or occupied ports, existing data roots, build failure, and failed health checks return nonzero.
- `GET /api/health` returning HTTP 200 is required for readiness. `COLDSTART_NO_OPEN=1` never attempts browser fallback.
- Existing callers without the new driver retain their interface. No tracked script is moved or ignored.
- Runtime cleanup and host isolation require separate live verification; a dry run is not a live smoke test.
