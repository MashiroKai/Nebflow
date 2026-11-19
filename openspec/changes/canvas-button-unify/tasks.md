# Tasks

- [x] Keep `.canvas-button` a pure identity hook and move the group material into one shared glass declaration.
- [x] Replace the written family disposition with a measured one in `scripts/check-canvas-families.mjs`.
- [x] Add the two-tree computed comparison (geometry + diff) as members of the `frontend` check profile.
- [x] Prove the negative control: a deliberately wrong disposition turns the check red (exit 1) and restoring it turns it green (exit 0).
- [ ] Close the remaining computed-style gaps for the mobile todo group (`.ta-pill`) and the desktop Canvas group (`.canvas-zoom-bar`) — group `border-radius`/`padding`/`gap`, member size/`color`, hover and active readings — the assertion for this is written and currently red.
