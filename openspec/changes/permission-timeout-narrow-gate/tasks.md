# Tasks

- [x] Replace the file-name-keyed command trigger with a risk-class trigger (`CommandRisk.classify`).
- [x] Keep the direct Write/Edit/MultiEdit file-path gate unchanged and require target ∧ risk for an opaque command.
- [x] Adopt the permission-landing bounded wait; do not add a second wait seam.
- [x] Gate the invariant in `scripts/check-permission-gate.mjs` (static profile).
- [ ] Shell-aware tokenization — NOT implemented (quoted inert text can still produce false positives; variable-concatenated names and nested `sh -c` can bypass).
- [ ] Redirection heuristic is exact — NOT implemented (`a>b` counts while `2>&1` and `> /dev/null` do not).
- [ ] Per-user credential locations outside the fixed class list — NOT covered.
