# Tasks

- [x] Declare one shared switch duration token (`--ds-dur-stack-switch: 0.55s`, `src/main/resources/web/css/design-system.css:363`).
- [x] Declare one shared nonlinear decelerating easing token (`--ds-ease-stack-switch`, `src/main/resources/web/css/design-system.css:364`) for all viewports.
- [x] Read the pair once per switch from the computed style instead of per-viewport literals (`src/main/resources/web/js/popArtifacts.js:965-968`).
- [x] Keep the existing compositor transforms, the buried-layer handoff and the queued navigation.
- [x] Switch immediately under reduced motion or when motion is disabled (`src/main/resources/web/js/popArtifacts.js:942`, `:960`).
- [ ] Run `tests/stack-continuity.spec.mjs` on both viewport variants — NOT executed in this batch.
- [ ] Browser measurement of both viewport sizes and of reduced motion — NOT executed in this batch.
- [ ] Token-level gate over the pair (presence, single declaration, curve shape) — NOT present in the repository and NOT added here.
