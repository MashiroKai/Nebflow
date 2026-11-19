# Canvas button families: declared disposition checked against measurement

## Behavior delta
Canvas chrome buttons keep one shared face: `.canvas-button` stays a pure identity hook, the button group's material lives in one shared glass declaration, and every registered family's *state-layer* owner is now a **measured** fact rather than a written declaration. `scripts/check-canvas-families.mjs` recomputes each family's state-layer owner from the CSS tree and fails when the ledger's `to:` disagrees; a family with no state-layer source can only pass by being named in `KNOWN_UNCOVERED` with a checkable reason. A new measurement test (`tests/canvas-button-unify-diff.spec.mjs`) compares real computed styles and bounding boxes at 375x812 and 1280x800 between the pre-consolidation tree (`12eef94b1^`, read-only, self-provisioned via `git archive`) and the current tree, in one process and one browser, forcing `:hover`/`:active` through CDP `CSS.forcePseudoState` with transitions frozen.

## Acceptance
- The retired second Canvas definition (`.canvas-button.canvas-button`) changes computed style on Canvas-button elements in exactly two properties — `border-radius` and `box-shadow` — in every state; background, border, filter, colour and box are byte-identical. The group-material move from `split.css` to `glass.css` is computed-neutral for `.canvas-zoom-bar`. The mobile todo group is fully unchanged.
- Every family disposition in the ledger equals its measured value; a deliberately wrong disposition turns the check red (exit 1) and restoring it turns it green (exit 0).
- The mobile todo group (`.ta-pill`) and the desktop Canvas group (`.canvas-zoom-bar`) still need their remaining computed-style gaps closed (group `border-radius`/`padding`/`gap`, member size/`color`, hover and active readings); the assertion for this is written and currently red.
- The measurement tests are members of the `frontend` check profile.
