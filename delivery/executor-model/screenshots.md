# Popup comparison

- **Viewport:** 1000 × 800 CSS pixels, device scale factor 1; light/dark follows the visual harness default (dark), Chinese locale.
- **Scenario:** the same mocked external Claude Code delegate conversation, with `sonnet` as the saved executor model. `before.png` uses the unchanged main web tree; `after.png` uses this branch. The post-change control is explicitly scoped to later dispatches, not the displayed session. Both images are sanitized synthetic fixtures, not live account data.
- **Fixture or command:** `tests/external-executor-model.spec.mjs` using the repository's zero-port visual harness and mocked executor HTTP responses. Set `NEBFLOW_PW_PATH` to a local playwright-core installation. For the baseline, set `EXTERNAL_MODEL_BEFORE_SHOT=delivery/executor-model/before.png` and pass an unchanged main web-root path as the script argument. For the branch, set `EXTERNAL_MODEL_AFTER_SHOT=delivery/executor-model/after.png` and use the default web root.

These screenshots demonstrate the popup surface, not backend persistence or actual CLI execution.
