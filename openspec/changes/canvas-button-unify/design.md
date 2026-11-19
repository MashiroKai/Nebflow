# Design

Canvas chrome buttons keep one shared face: `.canvas-button` stays a pure identity hook and the
button group's material lives in one shared glass declaration. Every registered family's
*state-layer* owner is now a measured fact rather than a written down declaration:
`scripts/check-canvas-families.mjs` recomputes each family's state-layer owner from the CSS tree
and fails when the ledger's `to:` disagrees, and a family with no state-layer source can only pass
by being named in `KNOWN_UNCOVERED` with a checkable reason. `tests/canvas-button-unify-geom.spec.mjs`
checks the current tree's geometry and material; `tests/canvas-button-unify-diff.spec.mjs` compares
real computed styles and bounding boxes between the pre-consolidation tree (`12eef94b1^`, read-only,
self-provisioned via `git archive`) and the current tree in one process and one browser, forcing
`:hover`/`:active` through CDP `CSS.forcePseudoState` with transitions frozen. The measured ledger the
gate reconciles against lives in `src/main/resources/web/css/glass.css` (the group material) and
`src/main/resources/web/css/split.css` (the retired second Canvas definition).
