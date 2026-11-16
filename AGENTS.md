# Agent instructions

## Branch and release discipline

- Use `feat/*` or `bug/*` for every batch branch. Existing `fix/*` branches are grandfathered; do not rename them retroactively.
- Do not push directly to USTC. Only the author opens USTC PRs and pushes release branches.
- After upstream verification, only the author lands changes on `main`, using fast-forward only. For release defects, create `bug/*` from `main`; after the fix reaches `main`, the author chooses which release line to update.
- Follow the complete branch model, guard instructions, and server-protection checklist in `CONTRIBUTING.md`. An override flag does not grant permission.
